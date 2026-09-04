package apap.adapter.anthropic

import apap.adapter.spi.AdapterErrorCategory
import apap.adapter.spi.AdapterException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * Provider固有のHTTPステータス／エラー種別を、SPIの8分類（02_システム仕様.md 2.11）へ写す。
 *
 * この分類がRetry・Fallback・Circuit Breakerの挙動を決める（15.1 Step1）ため、
 * **「分からないからTRANSIENT」にはしない**。再試行しても直らないものをTRANSIENTにすると、
 * 同じ失敗を予算いっぱい繰り返したうえでCBまで開く。
 *
 * ## 実APIの形
 *
 * エラー応答は `{"type":"error","error":{"type":"<種別>","message":"..."}}`。
 * HTTPステータスと`error.type`は基本的に1対1だが、**ステータスだけでは足りない**ケースがある
 * （下表の403と404）。そのため両方を見る。
 *
 * | status | error.type | 分類 | 理由 |
 * |---|---|---|---|
 * | 400 | invalid_request_error | INVALID_REQUEST | 入力の誤り。再試行しても直らない |
 * | 401 | authentication_error | AUTH_ERROR | Credentialの問題 |
 * | 403 | permission_error | AUTH_ERROR | 鍵は有効だが権限不足。利用側では直せずCredential側の問題 |
 * | 404 | not_found_error | MODEL_ERROR | 実質的にモデル名の誤り。Fallbackで別Modelへ回す価値がある |
 * | 413 | request_too_large | INVALID_REQUEST | 入力を縮めない限り直らない |
 * | 429 | rate_limit_error | RATE_LIMITED | `retry-after`ヘッダを拾う |
 * | 500 | api_error | TRANSIENT | Provider内部の一時障害 |
 * | 529 | overloaded_error | PROVIDER_UNAVAILABLE | 過負荷。同一Providerへの再試行より別候補が正しい |
 *
 * 詳細な根拠と、写しきれなかったケースは docs/adapter-spi-findings.md に記録している。
 */
object ErrorMapper {
    private val mapper = ObjectMapper()

    /** 成功以外のHTTP応答を[AdapterException]へ変換する。 */
    fun toException(reply: HttpReply): AdapterException {
        val errorNode = parseErrorNode(reply.body)
        val providerType = errorNode?.path("type")?.asText(null)
        val providerMessage = errorNode?.path("message")?.asText(null)
        val category = categoryOf(reply.status, providerType)
        return AdapterException(
            category = category,
            // Credentialは載せない。載るのはstatusとProvider側の分類・説明のみ（不変条件4）。
            message = "provider request failed: status=${reply.status}, type=${providerType ?: "unknown"}",
            retryAfter = retryAfterOf(reply),
            providerDetail = providerMessage,
        )
    }

    /**
     * ネットワーク例外（接続不能・読み取り中の切断など）の分類。
     * 応答が返っていない以上Provider側の状態は分からないため、再試行可能な[AdapterErrorCategory.TRANSIENT]
     * として扱う（2.11でTRANSIENTはRetry対象かつCB計上対象）。
     */
    fun toTransport(
        cause: Throwable,
        what: String,
    ): AdapterException =
        AdapterException(
            category = AdapterErrorCategory.TRANSIENT,
            message = "provider transport failure during $what: ${cause::class.simpleName}",
            cause = cause,
        )

    @Suppress("CyclomaticComplexMethod")
    private fun categoryOf(
        status: Int,
        providerType: String?,
    ): AdapterErrorCategory =
        when {
            // error.typeを先に見る。ステータスが同じでも意味が違うものを取り違えないため。
            providerType == "authentication_error" -> AdapterErrorCategory.AUTH_ERROR
            providerType == "permission_error" -> AdapterErrorCategory.AUTH_ERROR
            providerType == "not_found_error" -> AdapterErrorCategory.MODEL_ERROR
            providerType == "rate_limit_error" -> AdapterErrorCategory.RATE_LIMITED
            providerType == "overloaded_error" -> AdapterErrorCategory.PROVIDER_UNAVAILABLE
            providerType == "request_too_large" -> AdapterErrorCategory.INVALID_REQUEST
            providerType == "invalid_request_error" -> AdapterErrorCategory.INVALID_REQUEST
            providerType == "api_error" -> AdapterErrorCategory.TRANSIENT
            // error.typeが読めない場合のみステータスで判断する。
            status == STATUS_UNAUTHORIZED || status == STATUS_FORBIDDEN -> AdapterErrorCategory.AUTH_ERROR
            status == STATUS_NOT_FOUND -> AdapterErrorCategory.MODEL_ERROR
            status == STATUS_TOO_MANY_REQUESTS -> AdapterErrorCategory.RATE_LIMITED
            status == STATUS_OVERLOADED -> AdapterErrorCategory.PROVIDER_UNAVAILABLE
            status in CLIENT_ERROR_RANGE -> AdapterErrorCategory.INVALID_REQUEST
            status >= STATUS_SERVER_ERROR -> AdapterErrorCategory.TRANSIENT
            else -> AdapterErrorCategory.TRANSIENT
        }

    /**
     * `retry-after`は秒数またはHTTP-date。実APIは秒数を返すが、HTTP仕様上は日付もありうるため
     * 数値として読めない場合はnullにする（誤って0秒と解釈して即再試行しないため）。
     */
    private fun retryAfterOf(reply: HttpReply): Duration? {
        val seconds = reply.headers["retry-after"]?.trim()?.toLongOrNull()
        return seconds?.takeIf { it >= 0 }?.let { Duration.ofSeconds(it) }
    }

    private fun parseErrorNode(body: String): JsonNode? =
        runCatching { mapper.readTree(body).path("error").takeIf { !it.isMissingNode } }.getOrNull()

    private const val STATUS_UNAUTHORIZED = 401
    private const val STATUS_FORBIDDEN = 403
    private const val STATUS_NOT_FOUND = 404
    private const val STATUS_TOO_MANY_REQUESTS = 429
    private const val STATUS_SERVER_ERROR = 500
    private const val STATUS_OVERLOADED = 529
    private const val STATUS_CLIENT_ERROR_MIN = 400
    private const val STATUS_CLIENT_ERROR_MAX = 499
    private val CLIENT_ERROR_RANGE = STATUS_CLIENT_ERROR_MIN..STATUS_CLIENT_ERROR_MAX
}
