package apap.context

import apap.domain.model.conversation.Turn
import apap.domain.model.conversation.TurnRole
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.TokenCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ImportanceCompactionStrategyTest {
    private val tokensOf: (List<ContentPart>) -> TokenCount = { parts ->
        TokenCount(parts.sumOf { (it as ContentPart.Text).text.length })
    }

    private fun turn(
        seq: Int,
        role: TurnRole,
        text: String,
    ) = Turn(
        turnId = "t$seq",
        seq = seq,
        role = role,
        contentParts = listOf(ContentPart.Text(text)),
        createdAt = Instant.EPOCH,
    )

    @Test
    fun `keeps the highest-scored turns when over budget and restores original seq order`() {
        // SYSTEM (weight 3.0) outranks USER (2.0) which outranks ASSISTANT (1.0); each turn costs 3 tokens.
        val turns =
            listOf(
                turn(1, TurnRole.ASSISTANT, "aaa"),
                turn(2, TurnRole.SYSTEM, "bbb"),
                turn(3, TurnRole.USER, "ccc"),
            )
        // Budget for only 2 of the 3 turns (6 tokens): keeps SYSTEM and USER, drops ASSISTANT.
        val result = ImportanceCompactionStrategy().compact(turns, budgetTokens = 6, tokensOf)
        assertTrue(result.truncated)
        // Kept turns are restored to seq order (2 then 3), not score order.
        assertEquals(listOf(2, 3), result.turns.map { it.seq })
    }

    @Test
    fun `keeps everything and reports no truncation when it all fits`() {
        val turns = listOf(turn(1, TurnRole.USER, "a"), turn(2, TurnRole.ASSISTANT, "b"))
        val result = ImportanceCompactionStrategy().compact(turns, budgetTokens = 100, tokensOf)
        assertEquals(false, result.truncated)
        assertEquals(listOf(1, 2), result.turns.map { it.seq })
    }

    @Test
    fun `a custom TurnImportanceScorer overrides the default role weighting`() {
        // Custom scorer favors turns by seq (older = more important), inverting the default preference.
        val scorer = TurnImportanceScorer { turn -> -turn.seq.toDouble() }
        val turns = listOf(turn(1, TurnRole.ASSISTANT, "aaa"), turn(2, TurnRole.SYSTEM, "bbb"))
        val result = ImportanceCompactionStrategy(scorer).compact(turns, budgetTokens = 3, tokensOf)
        assertEquals(listOf(1), result.turns.map { it.seq })
    }
}
