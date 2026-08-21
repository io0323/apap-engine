package apap.domain.model.conversation

import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class ConversationTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun conversation() =
        Conversation(
            conversationId = ConversationId(testUlid('A')),
            sessionId = SessionId(testUlid('B')),
            tenantId = TenantId(testUlid('C')),
        )

    private fun turn(seq: Int) = Turn("t$seq", seq, TurnRole.USER, listOf(ContentPart.Text("hello")), createdAt = now)

    @Test
    fun `appends turns in sequence starting at 1`() {
        val withOneTurn = conversation().appendTurn(turn(1))
        assertEquals(1, withOneTurn.turnCount)
        val withTwoTurns = withOneTurn.appendTurn(turn(2))
        assertEquals(2, withTwoTurns.turnCount)
    }

    @Test
    fun `rejects a gap in turn sequence`() {
        assertThrows(TurnSequenceViolationException::class.java) {
            conversation().appendTurn(turn(2))
        }
    }

    @Test
    fun `rejects an out of order turn sequence`() {
        val withOneTurn = conversation().appendTurn(turn(1))
        assertThrows(TurnSequenceViolationException::class.java) { withOneTurn.appendTurn(turn(1)) }
        assertThrows(TurnSequenceViolationException::class.java) { withOneTurn.appendTurn(turn(3)) }
    }

    @Test
    fun `rejects appending to a DELETED conversation`() {
        val deleted = conversation().delete()
        assertThrows(ConversationDeletedException::class.java) { deleted.appendTurn(turn(1)) }
    }

    @Test
    fun `archive and delete change status`() {
        assertEquals(ConversationStatus.ARCHIVED, conversation().archive().status)
        assertEquals(ConversationStatus.DELETED, conversation().delete().status)
    }

    @Test
    fun `Turn requires seq at least 1 and at least one content part`() {
        assertThrows(IllegalArgumentException::class.java) { turn(0) }
        assertThrows(IllegalArgumentException::class.java) {
            Turn("t1", 1, TurnRole.USER, emptyList(), createdAt = now)
        }
    }
}
