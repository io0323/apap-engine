package apap.gateway.error

import apap.domain.model.vo.ErrorCode

/**
 * 13_API設計.md 13.4のエラー体系をHTTP層で表現する。
 *
 * **13.4のコード表そのものは複製しない。** `apap.domain.model.vo.ErrorCode` が既に
 * 13.4の全18コードとHTTPステータス・retryableを保持しているため、それを唯一の情報源として使う
 * （Gateway側に同じ表を書くと、いずれ必ず片方だけ更新されてズレる）。
 *
 * Gatewayが追加するのは、13.4に存在しない[NotImplemented]だけ。根拠はADR-0027
 * （13.1に定義はあるが対応するユースケースがapap-runtimeに無いエンドポイントを、
 * 黙って501で返さず明示的に区別するため）。
 */
sealed interface ApiError {
    /** 13.4の`code`。 */
    val code: String

    val status: Int

    val retryable: Boolean

    /** RFC 9457の`title`。 */
    val title: String get() = code.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** RFC 9457の`type`。 */
    val type: String get() = "$TYPE_BASE/${code.lowercase().replace('_', '-')}"

    /** 13.4の18コード。ステータス・retryableはドメイン側の定義をそのまま使う。 */
    data class Domain(
        val errorCode: ErrorCode,
    ) : ApiError {
        override val code: String get() = errorCode.name
        override val status: Int get() = errorCode.httpStatus
        override val retryable: Boolean get() = errorCode.retryableByDefault
    }

    /** ADR-0027: 本ビルドで提供していない13.1エンドポイント。 */
    data object NotImplemented : ApiError {
        override val code: String = "NOT_IMPLEMENTED"
        override val status: Int = HTTP_NOT_IMPLEMENTED
        override val retryable: Boolean = false
    }

    companion object {
        /**
         * 13.4の例示 `https://apap.example.internal/errors/rate-limit-exceeded` に合わせる。
         * RFC 9457の`type`は解決可能である必要はない安定識別子であり、環境ごとに変えると
         * クライアント側の分岐が壊れるため固定値とする（要件充足に影響しない実装判断のため
         * ADR化せずここに根拠を記す）。
         */
        const val TYPE_BASE = "https://apap.example.internal/errors"
        const val HTTP_NOT_IMPLEMENTED = 501

        fun of(errorCode: ErrorCode): ApiError = Domain(errorCode)
    }
}

/**
 * 13.4のエラー応答本体（RFC 9457 Problem Details + APAP拡張）。
 * JSONのフィールド名は13.4の例と完全一致させる（snake_case化は`GatewayJson`のNamingStrategyが行う）。
 */
data class ProblemDetails(
    val type: String,
    val title: String,
    val status: Int,
    val code: String,
    val detail: String,
    val requestId: String,
    val retryable: Boolean,
    val retryAfterMs: Long? = null,
) {
    companion object {
        fun of(
            error: ApiError,
            detail: String,
            requestId: String,
            retryAfterMs: Long? = null,
        ): ProblemDetails =
            ProblemDetails(
                type = error.type,
                title = error.title,
                status = error.status,
                code = error.code,
                detail = detail,
                requestId = requestId,
                retryable = error.retryable,
                retryAfterMs = retryAfterMs,
            )
    }
}

/**
 * Gatewayが直接送出する、[ApiError]が確定済みの例外。
 *
 * ここで投げてよいのはHTTP層固有の失敗（認証・リクエスト形式・未提供エンドポイント）だけ。
 * 実行時のビジネス上の失敗は`apap.execution.ExecutionFailedException`等として
 * apap-runtimeから上がってくるものを[toProblemDetails]で変換する（Gatewayで再判定しない）。
 */
class ApiException(
    val error: ApiError,
    override val message: String,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    constructor(
        errorCode: ErrorCode,
        message: String,
        retryAfterMs: Long? = null,
        cause: Throwable? = null,
    ) : this(ApiError.of(errorCode), message, retryAfterMs, cause)
}
