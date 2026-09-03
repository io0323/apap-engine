package apap.gateway

import apap.domain.port.IdGenerator
import apap.domain.port.MetricsRecorder
import apap.gateway.auth.TokenVerifier
import apap.gateway.auth.VerifiedCaller
import apap.gateway.config.GatewayConfig
import apap.gateway.error.ApiError
import apap.gateway.error.ApiException
import apap.gateway.error.toProblemDetails
import apap.gateway.json.GatewayJson
import apap.gateway.metrics.OpenMetricsRenderer
import apap.gateway.routes.adminRoutes
import apap.gateway.routes.discoveryRoutes
import apap.gateway.routes.executionRoutes
import apap.gateway.routes.opsRoutes
import apap.runtime.ApapEngine
import apap.runtime.UlidIdGenerator
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.JacksonConverter
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey

/** 認証済み呼び出し元を後続ハンドラへ渡すためのキー（暗黙のThreadLocalは使わない）。 */
val CallerKey: AttributeKey<VerifiedCaller> = AttributeKey("apap.gateway.caller")

/** 認証を通ったことが前提のハンドラで呼ぶ。未認証で到達した場合はバグなので例外にする。 */
val ApplicationCall.caller: VerifiedCaller
    get() =
        attributes.getOrNull(CallerKey)
            ?: error("caller is not set; the authentication phase did not run for this route")

/** 全応答に付与する`X-Request-Id`（13.5「追加ヘッダ」）。 */
const val REQUEST_ID_HEADER = "X-Request-Id"

/** 冪等化ヘッダ（13章共通事項）。 */
const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

/**
 * Gatewayのアプリケーション定義。
 *
 * HTTP層は薄いアダプタに徹し、ビジネスロジックは持たない——ルート定義は
 * 「DTO変換 → [ApapEngine]呼び出し → DTO変換」だけを行う。
 */
@Suppress("LongParameterList")
fun Application.apapGateway(
    engine: ApapEngine,
    config: GatewayConfig,
    tokenVerifier: TokenVerifier,
    metricsRenderer: OpenMetricsRenderer,
    idGenerator: IdGenerator = UlidIdGenerator(),
    lifecycle: GatewayLifecycle = GatewayLifecycle(),
) {
    // in-flightを数える。`server.stop`のgracePeriodだけではサスペンド中のリクエストが
    // 完遂しない（GatewayLifecycleのKDoc参照）ため、停止前に0になるまで待てるようにする。
    //
    // `ResponseSent`を終了点に使う: 応答を送り切った時点で減算されるので、SSEのように
    // 長く続く応答も「送り終わるまで実行中」と正しく数えられる。
    install(
        createApplicationPlugin(name = "InFlightTracking") {
            on(CallSetup) { call ->
                if (call.isTracked()) lifecycle.requestStarted()
                // NFR-PRF-001の計測区間「Gateway受信〜Adapter送信」の**始点**。
                // CallSetupはKtorのパイプライン最初のフェーズで、認証・JSON解析・DTO変換より前に走る
                // （ADR-0034）。終点はエンジン呼び出し直前で、finishGatewayPhaseが記録する。
                call.attributes.put(RECEIVED_AT_NANOS, System.nanoTime())
            }
            on(ResponseSent) { call -> if (call.isTracked()) lifecycle.requestFinished() }
        },
    )

    install(ContentNegotiation) {
        register(io.ktor.http.ContentType.Application.Json, JacksonConverter(GatewayJson.mapper))
    }

    // 13.5「全応答にX-Request-Id」。クライアント指定があれば尊重し、無ければ生成する
    // （分散トレースでクライアント側のIDと突き合わせられるようにするため）。
    //
    // 生成形式はULID: この値はそのままエンジンの`RequestId`になり、`RequestId`はULID形式を
    // 要求する（04_ドメイン設計.md 4.4）。UUIDを渡すと実行前にINVALID_REQUESTで弾かれる。
    // クライアント指定値もULIDでなければ採用しない（採用すると同じ理由で400になる）。
    install(CallId) {
        header(REQUEST_ID_HEADER)
        generate { idGenerator.newId() }
        verify { it.isNotBlank() && ULID_PATTERN.matches(it) }
    }

    // 13.4: 例外は必ずProblem Detailsへ。ここが唯一のエラー応答生成点。
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val requestId = call.callId ?: "unknown"
            val problem = cause.toProblemDetails(requestId)
            call.response.header(REQUEST_ID_HEADER, requestId)
            // 13.5「429/503にはRetry-After」。秒単位の整数（RFC 9110）。
            if (problem.status == TOO_MANY_REQUESTS || problem.status == SERVICE_UNAVAILABLE) {
                val retryAfterSeconds = problem.retryAfterMs?.let { (it + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND }
                call.response.header(
                    HttpHeaders.RetryAfter,
                    (retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS).toString(),
                )
            }
            call.respondText(
                text = GatewayJson.mapper.writeValueAsString(problem),
                contentType = PROBLEM_JSON,
                status = HttpStatusCode.fromValue(problem.status),
            )
        }
    }

    routing {
        // 認証不要（Kubernetes probe / スクレイプ対象）。
        opsRoutes(engine, metricsRenderer, lifecycle)

        // 以降は認証必須。ルートごとにverifyを呼ぶのではなく、
        // ルート定義側で`authenticated { }`を使うことで付け忘れを構造的に防ぐ。
        executionRoutes(engine, config, tokenVerifier)
        discoveryRoutes(engine, tokenVerifier)
        adminRoutes(engine, config, tokenVerifier)
    }
}

/**
 * `Authorization: Bearer`を検証して[CallerKey]へ格納する。
 *
 * ADR-0004: 検証は[TokenVerifier]（腐敗防止層）越しに行い、Gatewayはトークンの中身を知らない。
 */
suspend fun ApplicationCall.authenticate(tokenVerifier: TokenVerifier): VerifiedCaller {
    val header =
        request.header(HttpHeaders.Authorization)
            ?: throw ApiException(
                apap.domain.model.vo.ErrorCode.UNAUTHENTICATED,
                "Missing Authorization header",
            )
    val token =
        header.removePrefix(BEARER_PREFIX).takeIf { header.startsWith(BEARER_PREFIX) && it.isNotBlank() }
            ?: throw ApiException(
                apap.domain.model.vo.ErrorCode.UNAUTHENTICATED,
                "Authorization header must use the Bearer scheme",
            )
    val verified = tokenVerifier.verify(token)
    attributes.put(CallerKey, verified)
    return verified
}

/** Admin系APIのスコープ検査（13.1「管理系API（Admin権限）」）。 */
fun VerifiedCaller.requireAdminScope(adminScope: String) {
    if (!hasScope(adminScope)) {
        throw ApiException(
            apap.domain.model.vo.ErrorCode.PERMISSION_DENIED,
            "The token does not carry the required scope for administrative operations",
        )
    }
}

/** 13.1に定義はあるが本ビルドで提供していないエンドポイント（ADR-0027）。 */
fun notImplemented(
    method: String,
    path: String,
    reason: String,
): Nothing =
    throw ApiException(
        ApiError.NotImplemented,
        "$method $path is defined in the API design but is not provided by this build. $reason",
    )

/**
 * 排出の対象に数えるリクエストか。opsルート（probe/scrape）は除く——これらを数えると
 * Kubernetesがprobeを打ち続ける限りin-flightが0にならず、排出が永久に終わらない。
 */
private fun ApplicationCall.isTracked(): Boolean {
    val path = request.path()
    return path.startsWith("/v1") || path.startsWith("/admin")
}

/** [RECEIVED_AT_NANOS]: リクエスト受信時刻（`System.nanoTime()`）。`gateway` phaseの起点。 */
val RECEIVED_AT_NANOS: AttributeKey<Long> = AttributeKey("apap.gateway.receivedAtNanos")

/**
 * `apap_overhead_duration_seconds{phase="gateway"}` を記録する（2.19 / ADR-0034）。
 *
 * 受信からエンジン呼び出し直前までのGateway層の処理——Bearer検証・JSON解析・
 * Idempotency判定・DTO変換——がここに含まれる。この区間はエンジン内部の計測点では
 * 覆えないため、HTTP層が自分で記録する必要がある。計測はビジネスロジックではなく
 * 横断的関心事であり、「Gatewayにビジネスロジックを置かない」制約には抵触しない。
 */
fun ApplicationCall.finishGatewayPhase(metrics: MetricsRecorder) {
    val startedAt = attributes.getOrNull(RECEIVED_AT_NANOS) ?: return
    metrics.recordOverheadDuration(GATEWAY_PHASE, (System.nanoTime() - startedAt) / NANOS_PER_SECOND)
}

const val GATEWAY_PHASE = "gateway"
private const val NANOS_PER_SECOND = 1_000_000_000.0

private const val BEARER_PREFIX = "Bearer "
private const val TOO_MANY_REQUESTS = 429
private const val SERVICE_UNAVAILABLE = 503
private const val MILLIS_PER_SECOND = 1000L
private const val DEFAULT_RETRY_AFTER_SECONDS = 1L
private val PROBLEM_JSON = io.ktor.http.ContentType("application", "problem+json")

/** 04_ドメイン設計.md 4.4のULID（Crockford Base32、26文字、I/L/O/Uを除く）。 */
private val ULID_PATTERN = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")
