package apap.execution.streaming

import apap.context.ConversationManager
import apap.domain.model.conversation.Conversation
import apap.domain.model.conversation.TurnRole
import apap.domain.model.execution.StreamChunk
import apap.domain.model.execution.StreamChunkType
import apap.domain.model.execution.ToolCall
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.NormalizedError
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryConversationRepository
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** 02_システム仕様.md 2.8 step11のStreaming版: message_end/中断でのassistant turn記録。 */
class StreamingTurnRecorderTest {
    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val ids = InMemoryIdGenerator()
    private val events = InMemoryDomainEventPublisher()
    private val conversationRepository = InMemoryConversationRepository()
    private val conversationManager = ConversationManager(conversationRepository, clock, ids, events)
    private val recorder = StreamingTurnRecorder(conversationManager)
    private val conversationId = ConversationId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA1")

    private fun startConversation() {
        conversationRepository.save(
            Conversation(
                conversationId = conversationId,
                sessionId = SessionId("01ARZ3NDEKTSV4RRFFQ69G5FA2"),
                tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA3"),
            ),
        )
    }

    private fun contentDelta(text: String): StreamChunk =
        StreamChunk(type = StreamChunkType.CONTENT_DELTA, index = 0, delta = ContentPart.Text(text))

    private fun collect(chunks: Flow<StreamChunk>): List<StreamChunk> =
        runBlocking {
            recorder.record(conversationId, modelId, chunks).toList()
        }

    @Test
    fun `message_end persists the accumulated content as one assistant turn`() {
        startConversation()
        val chunks =
            flow {
                emit(contentDelta("Hello, "))
                emit(contentDelta("world!"))
                val usage = Usage.of(TokenCount(3), TokenCount(2))
                emit(StreamChunk(type = StreamChunkType.USAGE, index = 1, usage = usage))
                emit(StreamChunk(type = StreamChunkType.MESSAGE_END, index = 2))
            }

        val collected = collect(chunks)
        assertEquals(4, collected.size)

        val turns = conversationRepository.findTurns(conversationId, 1..Int.MAX_VALUE)
        assertEquals(1, turns.size)
        val turn = turns.single()
        assertEquals(TurnRole.ASSISTANT, turn.role)
        assertEquals(modelId, turn.modelUsed)
        assertEquals(Usage.of(TokenCount(3), TokenCount(2)), turn.usage)
        assertEquals(listOf(ContentPart.Text("Hello, world!")), turn.contentParts)
    }

    @Test
    fun `an interruption before message_end persists partial content with an interrupted marker`() {
        startConversation()
        val chunks =
            flow {
                emit(contentDelta("partial"))
                emit(
                    StreamChunk(
                        type = StreamChunkType.ERROR,
                        index = 1,
                        error =
                            NormalizedError(
                                code = ErrorCode.PROVIDER_ERROR,
                                category = AdapterErrorCategory.PROVIDER_UNAVAILABLE,
                                message = "boom",
                                retryable = false,
                                fallbackable = false,
                                cbRecordable = false,
                            ),
                    ),
                )
                // No MESSAGE_END: the flow ends here, simulating a mid-stream abort.
            }

        collect(chunks)

        val turns = conversationRepository.findTurns(conversationId, 1..Int.MAX_VALUE)
        assertEquals(1, turns.size)
        val turn = turns.single()
        assertEquals(TurnRole.ASSISTANT, turn.role)
        assertEquals(2, turn.contentParts.size)
        assertEquals(ContentPart.Text("partial"), turn.contentParts.first())
        assertEquals(ContentPart.Text("[interrupted]"), turn.contentParts.last())
    }

    @Test
    fun `tool call deltas are recorded as a readable summary`() {
        startConversation()
        val chunks =
            flow {
                emit(
                    StreamChunk(
                        type = StreamChunkType.TOOL_CALL_DELTA,
                        index = 0,
                        toolCallDelta =
                            ToolCall(callId = "call-1", toolName = "search", arguments = "{\"q\":\"apap\"}"),
                    ),
                )
                emit(StreamChunk(type = StreamChunkType.MESSAGE_END, index = 1))
            }

        collect(chunks)

        val turns = conversationRepository.findTurns(conversationId, 1..Int.MAX_VALUE)
        val content = turns.single().contentParts.single() as ContentPart.Text
        assertTrue(content.text.contains("search"))
        assertTrue(content.text.contains("apap"))
    }

    @Test
    fun `a stream with no content and no message_end persists nothing`() {
        startConversation()
        val chunks = flow<StreamChunk> { }

        collect(chunks)

        assertEquals(0, conversationRepository.findTurns(conversationId, 1..Int.MAX_VALUE).size)
    }
}
