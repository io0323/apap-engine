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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryConversationRepositoryTest {
    private val conversationId = ConversationId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA2")
    private val otherTenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA9")

    private fun conversation() =
        Conversation(
            conversationId = conversationId,
            sessionId = SessionId("01ARZ3NDEKTSV4RRFFQ69G5FA1"),
            tenantId = tenantId,
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

        repo.appendTurn(conversationId, tenantId, turn(1))
        repo.appendTurn(conversationId, tenantId, turn(2))

        assertEquals(2, repo.findById(conversationId, tenantId)?.turnCount)
        assertEquals(listOf(1), repo.findTurns(conversationId, tenantId, 1..1).map { it.seq })
    }

    @Test
    fun `appendTurn rejects a non-contiguous seq (propagates the domain exception)`() {
        val repo = InMemoryConversationRepository()
        repo.save(conversation())

        assertThrows(TurnSequenceViolationException::class.java) {
            repo.appendTurn(conversationId, tenantId, turn(2))
        }
    }

    @Test
    fun `delete is a logical delete, not a removal`() {
        val repo = InMemoryConversationRepository()
        repo.save(conversation())

        repo.delete(conversationId, tenantId)

        assertEquals(ConversationStatus.DELETED, repo.findById(conversationId, tenantId)?.status)
    }

    /**
     * P8後始末レビュー item3: 別テナントの`tenantId`で同じ`conversationId`を指定した場合、
     * 存在しない場合と区別せず扱う（[apap.domain.port.ConversationRepository]のKDoc参照）。
     */
    @Test
    fun `a conversationId belonging to another tenant is treated as not found`() {
        val repo = InMemoryConversationRepository()
        repo.save(conversation())

        assertNull(repo.findById(conversationId, otherTenantId))
        assertThrows(NoSuchConversationException::class.java) {
            repo.appendTurn(conversationId, otherTenantId, turn(1))
        }
        assertThrows(NoSuchConversationException::class.java) {
            repo.findTurns(conversationId, otherTenantId, 1..Int.MAX_VALUE)
        }
        assertThrows(NoSuchConversationException::class.java) {
            repo.delete(conversationId, otherTenantId)
        }
    }
}
