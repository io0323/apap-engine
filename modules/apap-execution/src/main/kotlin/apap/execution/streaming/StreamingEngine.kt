package apap.execution.streaming

import apap.adapter.spi.AdapterChunk
import apap.adapter.spi.AdapterChunkType
import apap.adapter.spi.AdapterException
import apap.adapter.spi.ProviderAdapter
import apap.domain.event.EventMetadata
import apap.domain.event.StreamAborted
import apap.domain.event.StreamClosed
import apap.domain.event.StreamOpened
import apap.domain.model.execution.ExecutionContext
import apap.domain.model.execution.StreamChunk
import apap.domain.model.execution.StreamChunkType
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.NormalizedError
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import apap.execution.mapping.ResponseMapper
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/** 09_状態遷移図.md 9.6。実装はStreaming Engine内に閉じる（03_基本設計.md 3.13）。 */
enum class StreamSessionState { OPENING, ACTIVE, DRAINING, CLOSED, ABORTED }

/**
 * 初回チャンク送出**前**にストリームが失敗したことを示す。02_システム仕様.md 2.10:
 * 「最初のチャンク送出前の失敗はFallback対象」。呼び出し側（FallbackEngineと同様の仕組み）は
 * この例外を捕捉してFallbackへ回す。送出**後**の失敗は例外ではなくERRORチャンクとしてFlow内で
 * 終端する（Fallback不可のため）。
 */
class StreamAbortedBeforeFirstChunkException(
    val normalizedError: NormalizedError,
    cause: Throwable? = null,
) : Exception(normalizedError.message, cause)

/**
 * 03_基本設計.md 2.10 Streaming Flow / 05_シーケンス設計.md 5.3。`AdapterStream`（pull型）を
 * 正規化済み`Flow<StreamChunk>`へ変換する。既存の[ProviderAdapter.asFlow]は使わず、heartbeat
 * 注入・アイドル/全体タイムアウト・ToolCallデルタ組立・バックプレッシャを併せ持つ専用実装とする。
 *
 * バックプレッシャ（256KB既定）は`Flow.buffer(capacity)`のRENDEZVOUS超過時サスペンドという
 * coroutine標準機構を用いて実現する。バイト数からアイテム数上限への換算は
 * [estimateChunkBytes]による概算であり、正確なシリアライズ後バイト数ではない（Gatewayの
 * 実送信バッファ実装（未実装、Presentation層）が正確なバイト計測を担う想定。要件充足に影響しない
 * 実装判断のためADR化せずここに根拠を残す）。
 */
class StreamingEngine(
    private val clock: Clock,
    private val eventPublisher: DomainEventPublisher,
    private val idGenerator: IdGenerator,
    private val config: StreamingConfig = StreamingConfig(),
) {
    @Suppress("LongMethod")
    fun normalize(
        adapterStream: ProviderAdapter.AdapterStream,
        ctx: ExecutionContext,
    ): Flow<StreamChunk> =
        channelFlow {
            var state = StreamSessionState.OPENING
            var firstChunkSent = false
            var index = 0
            val assembler = ToolCallAssembler()
            val startedAt = clock.now()
            var lastActivityAt = startedAt
            var completedNormally = false

            try {
                loop@ while (true) {
                    val now = clock.now()
                    if (elapsedSeconds(startedAt, now) >= config.overallTimeoutSeconds) {
                        abort(this, index, "stream overall timeout exceeded", ctx)
                        state = StreamSessionState.ABORTED
                        logger.debug("stream {} -> {}", ctx.requestId.value, state)
                        return@channelFlow
                    }
                    if (elapsedSeconds(lastActivityAt, now) >= config.idleTimeoutSeconds) {
                        abort(this, index, "stream idle timeout exceeded", ctx)
                        state = StreamSessionState.ABORTED
                        logger.debug("stream {} -> {}", ctx.requestId.value, state)
                        return@channelFlow
                    }

                    val outcome =
                        try {
                            pollNext(adapterStream, config.heartbeatSeconds)
                        } catch (e: AdapterException) {
                            if (!firstChunkSent) {
                                throw StreamAbortedBeforeFirstChunkException(ResponseMapper.normalizeError(e), e)
                            }
                            val error = ResponseMapper.normalizeError(e)
                            send(StreamChunk(type = StreamChunkType.ERROR, index = index++, error = error))
                            state = StreamSessionState.ABORTED
                            logger.debug("stream {} -> {}", ctx.requestId.value, state)
                            publishAborted(ctx, error.message)
                            return@channelFlow
                        }

                    if (outcome == null) {
                        send(StreamChunk(type = StreamChunkType.HEARTBEAT, index = index++))
                        continue@loop
                    }

                    when (outcome) {
                        is NextOutcome.EndOfStream -> {
                            state = StreamSessionState.DRAINING
                            send(StreamChunk(type = StreamChunkType.MESSAGE_END, index = index++))
                            state = StreamSessionState.CLOSED
                            logger.debug("stream {} -> {}", ctx.requestId.value, state)
                            completedNormally = true
                            publishClosed(ctx)
                            return@channelFlow
                        }
                        is NextOutcome.Chunk -> {
                            lastActivityAt = clock.now()
                            if (outcome.chunk.type == AdapterChunkType.MESSAGE_START) {
                                state = StreamSessionState.ACTIVE
                                firstChunkSent = true
                                publishOpened(ctx)
                            }
                            emitNormalized(this, outcome.chunk, assembler, index) { index = it }
                        }
                    }
                }
            } finally {
                if (!completedNormally) adapterStream.cancel()
            }
        }.buffer(capacity = estimateCapacity(config.backpressureBufferBytes))

    private suspend fun emitNormalized(
        scope: ProducerScope<StreamChunk>,
        raw: AdapterChunk,
        assembler: ToolCallAssembler,
        currentIndex: Int,
        updateIndex: (Int) -> Unit,
    ) {
        var idx = currentIndex
        val normalized = ResponseMapper.normalizeChunk(raw).let { it.copy(index = idx++) }
        val toolCallDelta = normalized.toolCallDelta
        if (normalized.type == StreamChunkType.TOOL_CALL_DELTA && toolCallDelta != null) {
            val completed = assembler.accept(toolCallDelta)
            if (completed != null) {
                scope.send(normalized.copy(toolCallDelta = completed))
            }
            // 未完結のデルタは組立中のため送出しない（完結時にまとめて送出する）。
        } else {
            scope.send(normalized)
        }
        updateIndex(idx)
    }

    private sealed interface NextOutcome {
        data class Chunk(
            val chunk: AdapterChunk,
        ) : NextOutcome

        object EndOfStream : NextOutcome
    }

    private suspend fun pollNext(
        stream: ProviderAdapter.AdapterStream,
        heartbeatSeconds: Long,
    ): NextOutcome? =
        withTimeoutOrNull(Duration.ofSeconds(heartbeatSeconds).toMillis()) {
            val chunk = stream.next()
            if (chunk == null) NextOutcome.EndOfStream else NextOutcome.Chunk(chunk)
        }

    private suspend fun abort(
        scope: ProducerScope<StreamChunk>,
        index: Int,
        reason: String,
        ctx: ExecutionContext,
    ) {
        val error =
            NormalizedError(
                code = ErrorCode.TIMEOUT,
                category = AdapterErrorCategory.PROVIDER_UNAVAILABLE,
                message = reason,
                retryable = false,
                fallbackable = false,
                cbRecordable = false,
            )
        scope.send(StreamChunk(type = StreamChunkType.ERROR, index = index, error = error))
        publishAborted(ctx, reason)
    }

    private fun elapsedSeconds(
        from: Instant,
        to: Instant,
    ): Long = Duration.between(from, to).seconds

    private fun estimateCapacity(bytes: Int): Int = (bytes / BYTES_PER_CHUNK_ESTIMATE).coerceAtLeast(1)

    private fun publishOpened(ctx: ExecutionContext) {
        eventPublisher.publish(StreamOpened(meta(ctx), ctx.requestId))
    }

    private fun publishClosed(ctx: ExecutionContext) {
        eventPublisher.publish(StreamClosed(meta(ctx), ctx.requestId))
    }

    private fun publishAborted(
        ctx: ExecutionContext,
        reason: String,
    ) {
        eventPublisher.publish(StreamAborted(meta(ctx), ctx.requestId, reason))
    }

    private fun meta(ctx: ExecutionContext): EventMetadata =
        EventMetadata(
            eventId = idGenerator.newId(),
            occurredAt = clock.now(),
            traceId = ctx.traceId,
            tenantId = ctx.tenantId,
            aggregateId = ctx.requestId.value,
            version = 0,
        )

    private companion object {
        const val BYTES_PER_CHUNK_ESTIMATE = 256
        val logger = LoggerFactory.getLogger(StreamingEngine::class.java)
    }
}
