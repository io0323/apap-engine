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
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.NormalizedError
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import apap.execution.mapping.ResponseMapper
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

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
 * バックプレッシャ（256KB既定）は[applyByteBackpressure]がバイト累積で判定する
 * （`Flow.buffer(固定アイテム数)`ではチャンクサイズが変動すると実バイト数が上限を超えうるため、
 * P6着手前レビューで是正——個々のチャンクの推定サイズ（[estimateBytes]）自体は
 * シリアライズ後の正確なバイト数ではない概算だが、その概算値を累積してゲートするため、
 * 大小さまざまなチャンクサイズが混在しても累積が上限を実際に超えない）。
 */
@Suppress("TooManyFunctions")
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
    ): Flow<StreamChunk> {
        // channelFlow's internal channel is NOT rendezvous by default (an unbuffered `.buffer()` call
        // is required to force capacity=0; otherwise it silently uses Channel.BUFFERED, ~64 items) —
        // without forcing rendezvous here, chunks would sail past applyByteBackpressure's gate into
        // that hidden buffer regardless of their byte size, defeating the whole point of item4's fix.
        val rendezvous = rawNormalize(adapterStream, ctx).buffer(capacity = 0)
        return applyByteBackpressure(rendezvous, config.backpressureBufferBytes.toLong())
    }

    @Suppress("LongMethod")
    private fun rawNormalize(
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
                            val pendingToolCalls = assembler.pendingCallIds()
                            if (pendingToolCalls.isNotEmpty()) {
                                // タスク要件item1: 不完全なまま終端したToolCallは黙って部分結果を
                                // 返さず、エラーとして扱う。
                                val error = unterminatedToolCallError(pendingToolCalls)
                                send(StreamChunk(type = StreamChunkType.ERROR, index = index++, error = error))
                                state = StreamSessionState.ABORTED
                                logger.debug("stream {} -> {}", ctx.requestId.value, state)
                                publishAborted(ctx, error.message)
                                return@channelFlow
                            }
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
        }

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
            val completed = assembler.accept(toolCallDelta, explicitComplete = raw.toolCallComplete)
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

    private fun unterminatedToolCallError(pendingCallIds: Set<String>): NormalizedError =
        NormalizedError(
            code = ErrorCode.INTERNAL_ERROR,
            category = AdapterErrorCategory.PROVIDER_UNAVAILABLE,
            message = "stream ended with unterminated tool call delta(s): ${pendingCallIds.joinToString()}",
            retryable = false,
            fallbackable = false,
            cbRecordable = false,
        )

    private fun elapsedSeconds(
        from: Instant,
        to: Instant,
    ): Long = Duration.between(from, to).seconds

    /**
     * [upstream]をバイト累積で判定する境界バッファ越しに中継する。producer（[upstream]を消費し
     * 内部[channel]へ積む側）は、積み込み前に累積バイト数が[maxBytes]を超えないか確認し、
     * 超える場合は空きができるまで[BACKPRESSURE_POLL_MILLIS]間隔でポーリング待機する
     * （単一チャンクが単独で[maxBytes]を超える場合でも、累積が0のときは必ず1件通す——
     * 巨大チャンク1件がストリーム全体をデッドロックさせないため）。
     *
     * 累積バイト数は、consumer側（呼び出し元のFlow収集者）が実際に要素を受け取り終えた後
     * （[emit]が返った後）にのみ減算する。channelの容量自体は[Channel.UNLIMITED]とし、
     * 実際のゲートは累積バイト数チェックのみが担う——channel容量を上限にすると、
     * アイテム数ベースの制限に逆戻りしてしまうため。
     */
    private fun applyByteBackpressure(
        upstream: Flow<StreamChunk>,
        maxBytes: Long,
    ): Flow<StreamChunk> =
        flow {
            coroutineScope {
                val channel = Channel<StreamChunk>(capacity = Channel.UNLIMITED)
                val bufferedBytes = AtomicLong(0)
                val producer =
                    launch {
                        try {
                            upstream.collect { chunk ->
                                val size = estimateBytes(chunk).toLong()
                                while (true) {
                                    val current = bufferedBytes.get()
                                    if (current == 0L || current + size <= maxBytes) {
                                        if (bufferedBytes.compareAndSet(current, current + size)) break
                                    } else {
                                        delay(BACKPRESSURE_POLL_MILLIS)
                                    }
                                }
                                channel.send(chunk)
                            }
                        } finally {
                            channel.close()
                        }
                    }
                for (chunk in channel) {
                    emit(chunk)
                    bufferedBytes.addAndGet(-estimateBytes(chunk).toLong())
                }
                producer.join()
            }
        }

    /**
     * チャンク1件の推定バイト数（UTF-16文字数を近似値として使う簡易概算、正確なシリアライズ後
     * バイト数ではない）。[applyByteBackpressure]はこの推定値を累積してゲートするため、
     * 過小評価が続くと実際のバイト量が[StreamingConfig.backpressureBufferBytes]をわずかに
     * 超えうるが、チャンクサイズが変動しても累積判定自体は常に機能する
     * （固定アイテム数上限のように「小さいチャンクの再送で上限が事実上無効化される」ことはない）。
     */
    private fun estimateBytes(chunk: StreamChunk): Int {
        val textLength = (chunk.delta as? ContentPart.Text)?.text?.length ?: 0
        val toolArgsLength = chunk.toolCallDelta?.arguments?.length ?: 0
        return (CHUNK_OVERHEAD_BYTES + textLength + toolArgsLength).coerceAtLeast(1)
    }

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
        const val CHUNK_OVERHEAD_BYTES = 32
        const val BACKPRESSURE_POLL_MILLIS = 5L
        val logger = LoggerFactory.getLogger(StreamingEngine::class.java)
    }
}
