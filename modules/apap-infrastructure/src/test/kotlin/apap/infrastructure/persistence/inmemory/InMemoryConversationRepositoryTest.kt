package apap.infrastructure.persistence.inmemory

import apap.domain.model.conversation.Conversation
import apap.domain.model.conversation.ConversationStatus
import apap.domain.model.conversation.Turn
import apap.domain.model.conversation.TurnRole
import apap.domain.model.conversation.TurnSequenceViolationException
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryConversationRepositoryTest {
    private val conversationId = ConversationId("01ARZ3NDEKTSV4RRFFQ69G5FA0")

    private fun conversation() =
        Conversation(
            conversationId = conversationId,
            sessionId = SessionId("01ARZ3NDEKTSV4RRFFQ69G5FA1"),
            tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA2"),
        )

    private fun turn(seq: Int) =
        Turn(
            turnId = "turn-$seq",
            seq = seq,
            role = TurnRole.USER,
            contentParts = listOf(ContentPart.Text("hello")),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    @Test
    fun `appendTurn is applied atomically and findTurns filters by seq range`() {
        val repo = InMemoryConversationRepository()
        repo.save(conversation())

        repo.appendTurn(conversationId, turn(1))
        repo.appendTurn(conversationId, turn(2))

        assertEquals(2, repo.findById(conversationId)?.turnCount)
        assertEquals(listOf(1), repo.findTurns(conversationId, 1..1).map { it.seq })
    }

    @Test
    fun `appendTurn rejects a non-contiguous seq (propagates the domain exception)`() {
        val repo = InMemoryConversationRepository()
        repo.save(conversation())

        assertThrows(TurnSequenceViolationException::class.java) {
            repo.appendTurn(conversationId, turn(2))
        }
    }

    @Test
    fun `delete is a logical delete, not a removal`() {
        val repo = InMemoryConversationRepository()
        repo.save(conversation())

        repo.delete(conversationId)

        assertEquals(ConversationStatus.DELETED, repo.findById(conversationId)?.status)
    }
}
