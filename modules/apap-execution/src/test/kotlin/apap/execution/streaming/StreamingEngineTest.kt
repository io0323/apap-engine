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
import apap.domain.model.vo.ContentPart
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
    fun `braces inside a string literal argument do not confuse the balance heuristic`() =
        runBlocking {
            // P6着手前レビューの指摘例そのもの: {"text": "use } carefully"}
            val stream =
                FakeAdapterStream(
                    mutableListOf(
                        AdapterChunk(AdapterChunkType.MESSAGE_START, 0),
                        AdapterChunk(
                            AdapterChunkType.TOOL_CALL_DELTA,
                            1,
                            toolCallDelta = ToolCall("call-1", "note", "{\"text\": \"use "),
                        ),
                        AdapterChunk(
                            AdapterChunkType.TOOL_CALL_DELTA,
                            2,
                            toolCallDelta = ToolCall("call-1", "note", "} carefully\"}"),
                        ),
                    ),
                )
            val engine = StreamingEngine(clock, events, ids, StreamingConfig())
            val chunks = withTimeout(5_000) { engine.normalize(stream, ctx()).toList() }
            val toolChunks = chunks.filter { it.type == StreamChunkType.TOOL_CALL_DELTA }
            assertEquals(1, toolChunks.size)
            assertEquals("{\"text\": \"use } carefully\"}", toolChunks.first().toolCallDelta?.arguments)
            assertEquals(StreamChunkType.MESSAGE_END, chunks.last().type)
        }

    @Test
    fun `escaped quotes inside the argument do not confuse the balance heuristic`() =
        runBlocking {
            // Argument text: {"quote": "she said \"hi } there\""}
            val stream =
                FakeAdapterStream(
                    mutableListOf(
                        AdapterChunk(AdapterChunkType.MESSAGE_START, 0),
                        AdapterChunk(
                            AdapterChunkType.TOOL_CALL_DELTA,
                            1,
                            toolCallDelta = ToolCall("call-1", "note", "{\"quote\": \"she said \\\"hi "),
                        ),
                        AdapterChunk(
                            AdapterChunkType.TOOL_CALL_DELTA,
                            2,
                            toolCallDelta = ToolCall("call-1", "note", "} there\\\"\"}"),
                        ),
                    ),
                )
            val engine = StreamingEngine(clock, events, ids, StreamingConfig())
            val chunks = withTimeout(5_000) { engine.normalize(stream, ctx()).toList() }
            val toolChunks = chunks.filter { it.type == StreamChunkType.TOOL_CALL_DELTA }
            assertEquals(1, toolChunks.size)
            assertEquals(
                "{\"quote\": \"she said \\\"hi } there\\\"\"}",
                toolChunks.first().toolCallDelta?.arguments,
            )
        }

    @Test
    fun `multiple tool calls arriving interleaved are assembled independently`() =
        runBlocking {
            val stream =
                FakeAdapterStream(
                    mutableListOf(
                        AdapterChunk(AdapterChunkType.MESSAGE_START, 0),
                        AdapterChunk(
                            AdapterChunkType.TOOL_CALL_DELTA,
                            1,
                            toolCallDelta = ToolCall("call-1", "a", "{\"x\":"),
                        ),
                        AdapterChunk(
                            AdapterChunkType.TOOL_CALL_DELTA,
                            2,
                            toolCallDelta = ToolCall("call-2", "b", "{\"y\":"),
                        ),
                        AdapterChunk(
                            AdapterChunkType.TOOL_CALL_DELTA,
                            3,
                            toolCallDelta = ToolCall("call-1", "a", "1}"),
                        ),
                        AdapterChunk(
                            AdapterChunkType.TOOL_CALL_DELTA,
                            4,
                            toolCallDelta = ToolCall("call-2", "b", "2}"),
                        ),
                    ),
                )
            val engine = StreamingEngine(clock, events, ids, StreamingConfig())
            val chunks = withTimeout(5_000) { engine.normalize(stream, ctx()).toList() }
            val toolChunks = chunks.filter { it.type == StreamChunkType.TOOL_CALL_DELTA }
            assertEquals(2, toolChunks.size)
            val byCallId = toolChunks.associate { it.toolCallDelta!!.callId to it.toolCallDelta!!.arguments }
            assertEquals("{\"x\":1}", byCallId["call-1"])
            assertEquals("{\"y\":2}", byCallId["call-2"])
        }

    @Test
    fun `stream ending with an unterminated tool call is an ERROR, not a silent partial result`() =
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
                        // Stream ends here (adapterStream.next() -> null) with an unbalanced buffer.
                    ),
                )
            val engine = StreamingEngine(clock, events, ids, StreamingConfig())
            val chunks = withTimeout(5_000) { engine.normalize(stream, ctx()).toList() }
            assertEquals(StreamChunkType.ERROR, chunks.last().type)
            assertTrue(chunks.none { it.type == StreamChunkType.MESSAGE_END })
            // No half-assembled ToolCall must ever be emitted.
            assertTrue(chunks.none { it.type == StreamChunkType.TOOL_CALL_DELTA })
        }

    @Test
    fun `an explicit toolCallComplete signal completes the call immediately, ADR-0019`() =
        runBlocking {
            val stream =
                FakeAdapterStream(
                    mutableListOf(
                        AdapterChunk(AdapterChunkType.MESSAGE_START, 0),
                        // Deliberately NOT balanced JSON (missing closing brace) — only the explicit
                        // signal, not the heuristic, should complete this.
                        AdapterChunk(
                            AdapterChunkType.TOOL_CALL_DELTA,
                            1,
                            toolCallDelta = ToolCall("call-1", "raw", "not-json-but-provider-says-done"),
                            toolCallComplete = true,
                        ),
                    ),
                )
            val engine = StreamingEngine(clock, events, ids, StreamingConfig())
            val chunks = withTimeout(5_000) { engine.normalize(stream, ctx()).toList() }
            val toolChunks = chunks.filter { it.type == StreamChunkType.TOOL_CALL_DELTA }
            assertEquals(1, toolChunks.size)
            assertEquals("not-json-but-provider-says-done", toolChunks.first().toolCallDelta?.arguments)
            assertEquals(StreamChunkType.MESSAGE_END, chunks.last().type)
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

    @Test
    fun `byte budget holds across a mix of small and large chunk sizes, not just item count`() =
        runBlocking {
            // A fixed-item-count buffer (the pre-fix bug: bytes/256, coerced to >=1) would admit
            // this many items regardless of their actual size. A byte-accumulating gate must not.
            val config = StreamingConfig(backpressureBufferBytes = 200)
            val sizes = listOf(1, 500, 2, 300, 1, 1000, 5, 1)
            val contentChunks =
                sizes.mapIndexed { i, len ->
                    AdapterChunk(AdapterChunkType.CONTENT_DELTA, i + 1, delta = TextContentPart("x".repeat(len)))
                }
            val allChunks = (listOf(AdapterChunk(AdapterChunkType.MESSAGE_START, 0)) + contentChunks).toMutableList()
            // FakeAdapterStream drains this list destructively (removeAt(0) per next() call), so
            // capture the original count now -- by assertion time it would otherwise read as 0.
            val totalChunkCount = allChunks.size
            val stream = FakeAdapterStream(allChunks)
            val engine = StreamingEngine(clock, events, ids, config)

            val gate = CompletableDeferred<Unit>()
            val collected = mutableListOf<StreamChunk>()
            val job =
                launch {
                    engine.normalize(stream, ctx()).collect { chunk ->
                        collected += chunk
                        // Block right after the very first item, as above.
                        if (collected.size == 1) gate.await()
                    }
                }
            delay(300)
            val callsWhileBlocked = stream.calls
            gate.complete(Unit)
            withTimeout(5_000) { job.join() }

            // Regardless of how the individual chunk sizes vary, the producer must not have been able
            // to pull every remaining chunk (9 total) while the very first one sat unconsumed: several
            // of the large ones (500/1000/300 bytes) each alone approach or exceed the 200-byte budget.
            assertTrue(
                callsWhileBlocked < totalChunkCount,
                "producer pulled all $callsWhileBlocked/$totalChunkCount chunks despite the byte budget",
            )
            // Once released, every chunk must still arrive, correctly sized, in order -- the byte gate
            // must throttle, not drop or corrupt data.
            val deltaTexts =
                collected
                    .filter {
                        it.type == StreamChunkType.CONTENT_DELTA
                    }.map { (it.delta as ContentPart.Text).text.length }
            assertEquals(sizes, deltaTexts)
        }
}
