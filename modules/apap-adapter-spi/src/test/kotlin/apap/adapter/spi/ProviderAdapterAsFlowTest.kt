package apap.adapter.spi

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderAdapterAsFlowTest {
    private class RecordingStream(
        private val chunks: List<AdapterChunk>,
    ) : ProviderAdapter.AdapterStream {
        private var index = 0
        var cancelled = false
            private set

        override suspend fun next(): AdapterChunk? {
            if (index >= chunks.size) return null
            return chunks[index++]
        }

        override fun cancel() {
            cancelled = true
        }
    }

    private fun chunk(index: Int) = AdapterChunk(type = AdapterChunkType.CONTENT_DELTA, index = index)

    @Test
    fun `emits every chunk in order until next returns null`(): Unit =
        runBlocking {
            val stream = RecordingStream(listOf(chunk(0), chunk(1), chunk(2)))
            val collected = stream.asFlow().toList()
            assertEquals(listOf(0, 1, 2), collected.map { it.index })
        }

    @Test
    fun `does not call cancel when the stream completes normally`(): Unit =
        runBlocking {
            val stream = RecordingStream(listOf(chunk(0)))
            stream.asFlow().toList()
            assertTrue(!stream.cancelled)
        }

    @Test
    fun `calls cancel when the collector stops before the stream is exhausted`(): Unit =
        runBlocking {
            val stream = RecordingStream(listOf(chunk(0), chunk(1), chunk(2)))
            val collected = stream.asFlow().take(1).toList()
            assertEquals(listOf(0), collected.map { it.index })
            assertTrue(stream.cancelled)
        }
}
