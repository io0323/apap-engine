package apap.execution.streaming

import apap.adapter.spi.AdapterChunk
import apap.adapter.spi.AdapterChunkType
import apap.adapter.spi.AdapterErrorCategory
import apap.adapter.spi.AdapterException
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.TextContentPart
import apap.adapter.spi.ToolCall
import apap.domain.model.execution.ExecutionContext
import apap.domain.model.execution.StreamChunk
import apap.domain.model.execution.StreamChunkType
import apap.execution.testsupport.testCanonicalRequest
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** 02_システム仕様.md 2.10 / 05_シーケンス設計.md 5.3 / 09_状態遷移図.md 9.6。 */
class StreamingEngineTest {
    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val events = InMemoryDomainEventPublisher()
    private val ids = InMemoryIdGenerator()

    private fun ctx() =
        ExecutionContext.start(
            testCanonicalRequest().requestId,
            testCanonicalRequest().tenantId,
            "trace",
            clock.now(),
            Duration.ofSeconds(60),
        )

    private class FakeAdapterStream(
        private val chunks: MutableList<AdapterChunk> = mutableListOf(),
        private val throwOnCall: Int? = null,
        private val hangAfter: Int? = null,
        private val onNext: (() -> Unit)? = null,
    ) : ProviderAdapter.AdapterStream {
        var calls = 0
            private set
        var cancelled = false
            private set

        override suspend fun next(): AdapterChunk? {
            calls += 1
            onNext?.invoke()
            if (throwOnCall == calls) {
                throw AdapterException(AdapterErrorCategory.TRANSIENT, "boom")
            }
            if (hangAfter != null && calls > hangAfter) {
                awaitCancellation()
            }
            return if (chunks.isEmpty()) null else chunks.removeAt(0)
        }

        override fun cancel() {
            cancelled = true
        }
    }

    @Test
    fun `normalizes a full stream and closes with MESSAGE_END`() =
        runBlocking {
            val stream =
                FakeAdapterStream(
                    mutableListOf(
                        AdapterChunk(AdapterChunkType.MESSAGE_START, 0),
                        AdapterChunk(AdapterChunkType.CONTENT_DELTA, 1, delta = TextContentPart("hi")),
                    ),
                )
            val engine = StreamingEngine(clock, events, ids, StreamingConfig())
            val chunks = withTimeout(5_000) { engine.normalize(stream, ctx()).toList() }
            assertEquals(StreamChunkType.MESSAGE_START, chunks[0].type)
            assertEquals(StreamChunkType.CONTENT_DELTA, chunks[1].type)
            assertEquals(StreamChunkType.MESSAGE_END, chunks.last().type)
            assertTrue(!stream.cancelled)
        }

    @Test
    fun `failure before the first chunk is thrown for the caller to fall back`() =
        runBlocking {
            val stream = FakeAdapterStream(throwOnCall = 1)
            val engine = StreamingEngine(clock, events, ids, StreamingConfig())
            assertThrows(StreamAbortedBeforeFirstChunkException::class.java) {
                runBlocking { engine.normalize(stream, ctx()).toList() }
            }
        }

    @Test
    fun `failure after the first chunk ends with an ERROR chunk, not an exception`() =
        runBlocking {
            val stream =
                FakeAdapterStream(mutableListOf(AdapterChunk(AdapterChunkType.MESSAGE_START, 0)), throwOnCall = 2)
            val engine = StreamingEngine(clock, events, ids, StreamingConfig())
            val chunks = withTimeout(5_000) { engine.normalize(stream, ctx()).toList() }
            assertEquals(StreamChunkType.MESSAGE_START, chunks[0].type)
            assertEquals(StreamChunkType.ERROR, chunks.last().type)
            assertTrue(stream.cancelled)
        }

    @Test
    fun `idle timeout aborts the stream with an ERROR chunk and cancels the adapter`() =
        runBlocking {
            val config = StreamingConfig(heartbeatSeconds = 1, idleTimeoutSeconds = 2, overallTimeoutSeconds = 300)
            val startChunks = mutableListOf(AdapterChunk(AdapterChunkType.MESSAGE_START, 0))
            val stream = FakeAdapterStream(startChunks, hangAfter = 1)
            val engine = StreamingEngine(clock, events, ids, config)
            val chunks =
                withTimeout(10_000) {
                    val result = mutableListOf<StreamChunk>()
                    engine.normalize(stream, ctx()).collect { chunk ->
                        result += chunk
                        // Simulate wall-clock time passing between heartbeats by advancing the injected
                        // clock past the idle threshold once we've seen the first real chunk.
                        if (chunk.type == StreamChunkType.MESSAGE_START) clock.advanceBy(config.idleTimeoutSeconds + 1)
                    }
                    result
                }
            assertEquals(StreamChunkType.ERROR, chunks.last().type)
            assertTrue(stream.cancelled)
        }

    @Test
    fun `assembles a ToolCall delta split across multiple chunks before emitting it`() =
        runBlocking {
            val stream =
                FakeAdapterStream(
                    mutableListOf(
                        AdapterChunk(AdapterChunkType.MESSAGE_START, 0),
                        AdapterChunk(
                            AdapterChunkType.TOOL_CALL_DELTA,
                            1,
                            toolCallDelta = ToolCall("call-1", "get_weather", "{\"city\":"),
                        ),
                        AdapterChunk(
                            AdapterChunkType.TOOL_CALL_DELTA,
                            2,
                            toolCallDelta = ToolCall("call-1", "get_weather", "\"Tokyo\"}"),
                        ),
                    ),
                )
            val engine = StreamingEngine(clock, events, ids, StreamingConfig())
            val chunks = withTimeout(5_000) { engine.normalize(stream, ctx()).toList() }
            val toolChunks = chunks.filter { it.type == StreamChunkType.TOOL_CALL_DELTA }
            // The intermediate (unbalanced-braces) fragment must not be emitted; only the completed call is.
            assertEquals(1, toolChunks.size)
            assertEquals("{\"city\":\"Tokyo\"}", toolChunks.first().toolCallDelta?.arguments)
        }

    @Test
    fun `backpressure suspends the producer once the buffer capacity is reached`() =
        runBlocking {
            // A tiny byte budget forces a small buffer capacity (~1 item).
            val config = StreamingConfig(backpressureBufferBytes = 1)
            val manyChunks =
                (1..50)
                    .map { AdapterChunk(AdapterChunkType.CONTENT_DELTA, it, delta = TextContentPart("x")) }
                    .toMutableList()
            manyChunks.add(0, AdapterChunk(AdapterChunkType.MESSAGE_START, 0))
            val stream = FakeAdapterStream(manyChunks)
            val engine = StreamingEngine(clock, events, ids, config)

            val gate = CompletableDeferred<Unit>()
            val job =
                launch {
                    engine.normalize(stream, ctx()).collect {
                        // The very first collected item blocks here until the test releases the gate,
                        // simulating a slow downstream consumer.
                        gate.await()
                    }
                }
            delay(300)
            val callsWhileConsumerBlocked = stream.calls
            gate.complete(Unit)
            withTimeout(5_000) { job.join() }

            // With a ~1-item buffer, the producer must not be able to race far ahead of a consumer
            // that is still blocked on the very first item.
            assertTrue(
                callsWhileConsumerBlocked <= 3,
                "producer ran ahead of a blocked consumer: calls=$callsWhileConsumerBlocked",
            )
        }
}
