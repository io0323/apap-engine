package apap.gateway.routes

import apap.api.ApapStreamChunk
import apap.domain.model.vo.CapabilityId
import apap.gateway.IDEMPOTENCY_KEY_HEADER
import apap.gateway.REQUEST_ID_HEADER
import apap.gateway.auth.TokenVerifier
import apap.gateway.authenticate
import apap.gateway.catalog.EndpointCatalog
import apap.gateway.config.GatewayConfig
import apap.gateway.dto.ChatRequestDto
import apap.gateway.dto.EmbeddingRequestDto
import apap.gateway.dto.toApapRequest
import apap.gateway.dto.toChatResponseDto
import apap.gateway.error.toProblemDetails
import apap.gateway.finishGatewayPhase
import apap.gateway.notImplemented
import apap.gateway.sse.HEARTBEAT_EVENT
import apap.gateway.sse.SseEvent
import apap.gateway.sse.errorEvent
import apap.gateway.sse.toSseEvent
import apap.runtime.ApapEngine
import apap.runtime.UlidIdGenerator
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Writer

/**
 * 13_API設計.md 13.1「実行系API」。
 *
 * **すべてのCapabilityは同一のハンドラで処理する。** `ApapEngine.execute`はCapability非依存
 * （`capabilityId`による候補解決はエンジン側のRouting）なので、Gatewayはパスと`CapabilityId`の
 * 対応付けだけを行う。ここにCapability別の分岐ロジックを置くと、まさに
 * 「Gatewayにビジネスロジックを置く」ことになる。
 *
 * 対応するProvider/Modelが登録されていないCapabilityには、エンジンが
 * `CAPABILITY_NOT_AVAILABLE`(404) / `NO_CANDIDATE_AVAILABLE`(503) を返す——
 * これは13.4に沿った正しい応答であって「黙って501」ではない。
 * 一方、apap-runtimeにユースケース自体が存在しないエンドポイント（batches/memories）は
 * [EndpointCatalog]に基づき理由付きの`NOT_IMPLEMENTED`(501)を返す（ADR-0027）。
 */
fun Route.executionRoutes(
    engine: ApapEngine,
    config: GatewayConfig,
    tokenVerifier: TokenVerifier,
) {
    CAPABILITY_BY_PATH.forEach { (path, capabilityId) ->
        post(path) {
            val caller = call.authenticate(tokenVerifier)
            val body = call.receive<ChatRequestDto>()
            val request =
                body.toApapRequest(
                    tenantId = caller.tenantId,
                    principal = caller.principal,
                    capabilityId = capabilityId,
                    requestId = call.requestId(),
                    idempotencyKey = call.request.header(IDEMPOTENCY_KEY_HEADER),
                )
            // ここまでがGateway層の付加分（NFR-PRF-001の区間の前半、ADR-0034）。
            call.finishGatewayPhase(engine.metrics)
            if (body.stream) {
                call.respondSse(engine.executeStream(request), request.requestId.orEmpty(), config)
            } else {
                call.response.header(REQUEST_ID_HEADER, request.requestId.orEmpty())
                call.respond(engine.execute(request).toChatResponseDto(body.modelAlias))
            }
        }
    }

    // Embeddingは13.2で別のリクエスト形（`inputs`）を持つ。
    post("/v1/embeddings") {
        val caller = call.authenticate(tokenVerifier)
        val body = call.receive<EmbeddingRequestDto>()
        val request =
            body.toApapRequest(
                tenantId = caller.tenantId,
                principal = caller.principal,
                capabilityId = CapabilityId("embedding"),
                requestId = call.requestId(),
                idempotencyKey = call.request.header(IDEMPOTENCY_KEY_HEADER),
            )
        call.finishGatewayPhase(engine.metrics)
        call.response.header(REQUEST_ID_HEADER, request.requestId.orEmpty())
        call.respond(engine.execute(request).toChatResponseDto(body.modelAlias))
    }

    // 13.1にあるが本ビルドでは提供していない実行系（ADR-0027）。
    notImplementedRoutes(listOf("/v1/batches", "/v1/memories"))
}

/**
 * 13.1のパスとCapabilityIdの対応。`CapabilityId`の綴りは`[a-z_]{3,40}`（04_ドメイン設計.md 4.4）。
 * Capabilityが実際に利用可能かどうかはテナントのPolicyとModel登録状況で決まるため、
 * ここで存在確認はしない（するとGatewayが権限判断を持つことになる）。
 */
private val CAPABILITY_BY_PATH =
    mapOf(
        "/v1/chat" to CapabilityId("chat"),
        "/v1/completions" to CapabilityId("completion"),
        "/v1/images/generations" to CapabilityId("image_generation"),
        "/v1/images/edits" to CapabilityId("image_edit"),
        "/v1/images/analyses" to CapabilityId("image_analysis"),
        "/v1/audio/transcriptions" to CapabilityId("speech_to_text"),
        "/v1/audio/speech" to CapabilityId("text_to_speech"),
        "/v1/audio/translations" to CapabilityId("audio_translation"),
        "/v1/videos/analyses" to CapabilityId("video_analysis"),
    )

/**
 * 13.3 SSE Streaming。
 *
 * Ktorの`sse{}`ビルダはGETルート用で、13.1が要求する「POST /v1/chat（`stream=true`）」の形に
 * 使えないため、`text/event-stream`をそのまま書き出す（13.3のワイヤ形式に厳密準拠させる
 * 必要があり、フレーム生成を自前で持つほうが検証もしやすい）。
 *
 * - `Cache-Control: no-store`（13.5）
 * - チャンクが来ないまま[GatewayConfig.sseHeartbeatSeconds]（既定15秒、2.10/13.3）経過したら
 *   `event: heartbeat`を送って接続を維持する
 * - 異常時は`event: error`（13.4形式）で終端する（13.3）
 */
private suspend fun ApplicationCall.respondSse(
    chunks: Flow<ApapStreamChunk>,
    responseId: String,
    config: GatewayConfig,
) {
    response.header(REQUEST_ID_HEADER, responseId)
    response.header(HttpHeaders.CacheControl, "no-store")
    respondTextWriter(contentType = ContentType.Text.EventStream) {
        writeSseStream(chunks, responseId, config.sseHeartbeatSeconds)
    }
}

/**
 * チャンク列をSSEフレームとして書き出す。heartbeatの挿入とエラー終端を行う。
 * [Writer]への書き込みごとにflushしないと、SSEの「逐次届く」性質が失われる。
 */
internal suspend fun Writer.writeSseStream(
    chunks: Flow<ApapStreamChunk>,
    responseId: String,
    heartbeatSeconds: Long,
) {
    coroutineScope {
        val channel = chunks.produceIn(this)
        try {
            var streaming = true
            while (streaming) {
                val received =
                    withTimeoutOrNull(heartbeatSeconds * MILLIS_PER_SECOND) {
                        runCatching { channel.receive() }
                    }
                when {
                    // 間隔内にチャンクが来なかった -> 接続維持のためheartbeat。
                    received == null -> writeEvent(HEARTBEAT_EVENT)
                    // チャンネルが閉じた -> ストリーム正常終了。
                    received.exceptionOrNull() is ClosedReceiveChannelException -> streaming = false
                    received.isFailure -> throw received.exceptionOrNull()!!
                    else -> writeEvent(received.getOrThrow().toSseEvent(responseId))
                }
            }
        } catch (
            // 13.3「異常時はevent: errorで終端」。SSEは既に200で開始しておりHTTPステータスでは
            // 失敗を伝えられないため、**どの例外でも**errorフレームを出して終端する必要がある。
            // 種別を絞ると、絞り漏れた例外がフレーム無しで接続だけ切れる形になり最も分かりにくい。
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            writeEvent(errorEvent(e.toProblemDetails(responseId)))
        } finally {
            channel.cancel()
        }
    }
}

/** SSEフレーム: `event: <name>` / `data: <json>` / 空行。 */
private fun Writer.writeEvent(event: SseEvent) {
    write("event: ${event.name}\n")
    // dataが複数行になる場合もSSEの規約どおり行ごとに`data: `を付ける。
    event.data.lineSequence().forEach { line -> write("data: $line\n") }
    write("\n")
    flush()
}

private fun ApplicationCall.requestId(): String = callId ?: UlidIdGenerator().newId()

/**
 * [EndpointCatalog]がNOT_IMPLEMENTEDとしているパスに、理由付きの501を返すルートを登録する。
 * カタログを唯一の情報源にすることで「表にあるのにルートが無い（404になる）」を防ぐ。
 */
internal fun Route.notImplementedRoutes(pathPrefixes: List<String>) {
    EndpointCatalog.notImplemented
        .filter { spec -> pathPrefixes.any { spec.path.startsWith(it) } }
        .forEach { spec -> registerNotImplementedRoute(spec.method, spec.path, spec.unavailableReason.orEmpty()) }
}

private const val MILLIS_PER_SECOND = 1000L
