package apap.context

import apap.domain.model.conversation.Turn
import apap.domain.model.conversation.TurnRole
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.TokenCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class TruncateOldestCompactionStrategyTest {
    private val strategy = TruncateOldestCompactionStrategy()
    private val tokensOf: (List<ContentPart>) -> TokenCount = { parts ->
        TokenCount(parts.sumOf { (it as ContentPart.Text).text.length })
    }

    private fun turn(
        seq: Int,
        text: String,
    ) = Turn(
        turnId = "t$seq",
        seq = seq,
        role = TurnRole.USER,
        contentParts = listOf(ContentPart.Text(text)),
        createdAt = Instant.EPOCH,
    )

    @Test
    fun `keeps all turns when they fit within budget`() {
        val turns = listOf(turn(1, "12345"), turn(2, "678"))
        val result = strategy.compact(turns, budgetTokens = 8, tokensOf)
        assertFalse(result.truncated)
        assertEquals(listOf(1, 2), result.turns.map { it.seq })
    }

    @Test
    fun `drops the oldest turns first when over budget`() {
        val turns = listOf(turn(1, "12345"), turn(2, "678"))
        val result = strategy.compact(turns, budgetTokens = 3, tokensOf)
        assertTrue(result.truncated)
        assertEquals(listOf(2), result.turns.map { it.seq })
    }

    @Test
    fun `empty history is never truncated`() {
        val result = strategy.compact(emptyList(), budgetTokens = 0, tokensOf)
        assertFalse(result.truncated)
        assertTrue(result.turns.isEmpty())
    }
}
