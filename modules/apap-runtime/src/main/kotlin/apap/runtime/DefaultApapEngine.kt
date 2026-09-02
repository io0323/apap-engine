package apap.runtime

import apap.api.ApapHealth
import apap.api.ApapRequest
import apap.api.ApapResponse
import apap.api.ApapStreamChunk
import apap.api.ApapStreamChunkType
import apap.api.CapabilityDescriptor
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.StreamChunkType
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import apap.domain.port.IdGenerator
import apap.execution.ExecutionEngine
import apap.plugin.PluginManager
import apap.provider.CapabilityDiscoveryQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ApapEngine]の実装。[ApapRequest]/[ApapResponse]/[ApapStreamChunk]（apap-api、公開DTO）と
 * `CanonicalRequest`/`CanonicalResponse`/`StreamChunk`（apap-domain、内部表現）の変換を行い、
 * 実処理は[executionEngine]（`apap.execution.ExecutionEngine`、`ExecutionEngineComposer`が
 * 組み立てたもの）へ委譲する。
 */
@Suppress("LongParameterList")
internal class DefaultApapEngine(
    private val executionEngine: ExecutionEngine,
    private val capabilityDiscoveryQuery: CapabilityDiscoveryQuery,
    private val idGenerator: IdGenerator,
    override val admin: ApapAdmin,
    override val health: ApapHealth,
    private val pluginManager: PluginManager?,
    private val drainTimeout: Duration = DEFAULT_DRAIN_TIMEOUT,
) : ApapEngine {
    private val draining = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val inFlight = AtomicInteger(0)

    override suspend fun execute(request: ApapRequest): ApapResponse {
        rejectIfDraining()
        inFlight.incrementAndGet()
        try {
            return normalizingFailures { executionEngine.execute(request.toCanonical(idGenerator)).toApi() }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override fun executeStream(request: ApapRequest): Flow<ApapStreamChunk> =
        executionEngine
            .executeStream(request.toCanonical(idGenerator))
            .onStart {
                rejectIfDraining()
                inFlight.incrementAndGet()
            }.onCompletion { inFlight.decrementAndGet() }
            .map { it.toApi() }
            .catch { throw it.toApapException() }

    override suspend fun capabilities(tenantId: TenantId): List<CapabilityDescriptor> =
        capabilityDiscoveryQuery.listCapabilities(tenantId).map {
            CapabilityDescriptor(it.capabilityId, it.name, it.streamable, it.inputSchema, it.outputSchema)
        }

    /**
     * 実行系の内部例外を公開例外[ApapException]へ正規化する。埋込ホストからは
     * `apap-execution`/`apap-routing`/`apap-context`が見えない（`implementation`スコープ）ため、
     * 内部例外をそのまま投げるとホスト側で型として捕捉できない。
     */
    private inline fun <T> normalizingFailures(block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            throw e.toApapException()
        }

    private fun rejectIfDraining() {
        check(!draining.get()) { "ApapEngine is closing (or already closed); new requests are rejected" }
    }

    override fun close() {
        if (!draining.compareAndSet(false, true)) return
        val deadline = System.nanoTime() + drainTimeout.toNanos()
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(DRAIN_POLL_INTERVAL_MS)
        }
        pluginManager?.let { manager ->
            manager.loadedPluginIds().forEach { pluginId -> runCatching { manager.unload(pluginId) } }
        }
        closed.set(true)
    }

    private companion object {
        val DEFAULT_DRAIN_TIMEOUT: Duration = Duration.ofSeconds(30)
        const val DRAIN_POLL_INTERVAL_MS = 20L
    }
}

private fun ApapRequest.toCanonical(idGenerator: IdGenerator): CanonicalRequest =
    CanonicalRequest(
        requestId = RequestId(requestId ?: idGenerator.newId()),
        tenantId = tenantId,
        principal = principal,
        capabilityId = capabilityId,
        modelAlias = modelAlias,
        input = input,
        params = params,
        tools = tools,
        outputSchema = outputSchema,
        conversationId = conversationId,
        sessionId = sessionId,
        idempotencyKey = idempotencyKey,
        timeoutBudget = timeoutBudget,
        traceId = traceId ?: idGenerator.newId(),
    )

private fun apap.domain.model.execution.CanonicalResponse.toApi(): ApapResponse =
    ApapResponse(
        responseId = responseId,
        requestId = requestId.value,
        output = output,
        toolCalls = toolCalls,
        finishReason = finishReason,
        usage = usage,
        cost = cost,
        cached = cached,
    )

private fun apap.domain.model.execution.StreamChunk.toApi(): ApapStreamChunk =
    ApapStreamChunk(
        type = type.toApi(),
        index = index,
        delta = delta,
        toolCallDelta = toolCallDelta,
        usage = usage,
        error = error,
    )

private fun StreamChunkType.toApi(): ApapStreamChunkType =
    when (this) {
        StreamChunkType.MESSAGE_START -> ApapStreamChunkType.MESSAGE_START
        StreamChunkType.CONTENT_DELTA -> ApapStreamChunkType.CONTENT_DELTA
        StreamChunkType.TOOL_CALL_DELTA -> ApapStreamChunkType.TOOL_CALL_DELTA
        StreamChunkType.USAGE -> ApapStreamChunkType.USAGE
        StreamChunkType.MESSAGE_END -> ApapStreamChunkType.MESSAGE_END
        StreamChunkType.ERROR -> ApapStreamChunkType.ERROR
        StreamChunkType.HEARTBEAT -> ApapStreamChunkType.HEARTBEAT
    }
