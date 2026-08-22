package apap.context

import apap.domain.model.conversation.Turn
import apap.domain.model.conversation.TurnRole
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.TokenCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SummarizeCompactionStrategyTest {
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

    private val fakeSummarizer = SummarizationPort { summarized -> turn(seq = summarized.first().seq, text = "SS") }

    @Test
    fun `construction without opting in throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            SummarizeCompactionStrategy(fakeSummarizer, optedIn = false)
        }
    }

    @Test
    fun `summarizes the oldest turns that do not fit and keeps the newest ones verbatim`() {
        val strategy = SummarizeCompactionStrategy(fakeSummarizer, optedIn = true)
        // "12345"(5) + "678"(3) + "9"(1) = 9 chars total. Budget 3 fits only the newest turn ("9", 1
        // char), leaving 2 tokens of headroom -- exactly enough for the 2-char summary of the rest.
        val turns = listOf(turn(1, "12345"), turn(2, "678"), turn(3, "9"))
        val result = strategy.compact(turns, budgetTokens = 3, tokensOf)

        assertTrue(result.truncated)
        assertEquals(
            "SS",
            (
                result.turns
                    .first()
                    .contentParts
                    .single() as ContentPart.Text
            ).text,
        )
        // The summary Turn borrows the seq of the oldest turn it summarizes (turn 1).
        assertEquals(listOf(1, 3), result.turns.map { it.seq })
    }

    @Test
    fun `falls back to dropping the summarized region entirely when even the summary does not fit`() {
        val hugeSummary = "x".repeat(50)
        val hugeSummarizer = SummarizationPort { summarized -> turn(seq = summarized.first().seq, text = hugeSummary) }
        val strategy = SummarizeCompactionStrategy(hugeSummarizer, optedIn = true)
        val turns = listOf(turn(1, "12345"), turn(2, "9"))

        val result = strategy.compact(turns, budgetTokens = 2, tokensOf)

        assertTrue(result.truncated)
        assertEquals(listOf(2), result.turns.map { it.seq })
    }

    @Test
    fun `no summarization needed when everything fits`() {
        val strategy = SummarizeCompactionStrategy(fakeSummarizer, optedIn = true)
        val turns = listOf(turn(1, "12"), turn(2, "34"))
        val result = strategy.compact(turns, budgetTokens = 100, tokensOf)

        assertEquals(false, result.truncated)
        assertEquals(listOf(1, 2), result.turns.map { it.seq })
    }
}
