package apap.domain.service.conversation

import apap.domain.model.conversation.Turn
import apap.domain.model.conversation.TurnRole
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.TokenCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ContextAssemblyServiceTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    // 1トークン = 1ContentPart.Textという単純化した見積り関数（テスト専用）
    private val tokensOf: (List<ContentPart>) -> TokenCount = { parts -> TokenCount(parts.size) }

    private fun turn(seq: Int) = Turn("t$seq", seq, TurnRole.USER, listOf(ContentPart.Text("x")), createdAt = now)

    @Test
    fun `computeBudget subtracts maxOutputTokens and the safety margin`() {
        // 1000 - 100(maxOutput) - ceil(1000*0.05)=50 = 850
        assertEquals(850, ContextAssemblyService.computeBudget(1000, 100, 0.05))
    }

    @Test
    fun `computeBudget rejects maxOutputTokens not smaller than contextWindow`() {
        assertThrows(IllegalArgumentException::class.java) { ContextAssemblyService.computeBudget(100, 100, 0.05) }
    }

    @Test
    fun `computeBudget rejects a non positive contextWindow`() {
        assertThrows(IllegalArgumentException::class.java) { ContextAssemblyService.computeBudget(0, 0, 0.05) }
    }

    @Test
    fun `computeBudget rejects a negative safetyMarginRatio`() {
        assertThrows(IllegalArgumentException::class.java) { ContextAssemblyService.computeBudget(1000, 100, -0.01) }
    }

    @Test
    fun `computeBudget rejects a negative maxOutputTokens`() {
        assertThrows(IllegalArgumentException::class.java) { ContextAssemblyService.computeBudget(1000, -1, 0.05) }
    }

    @Test
    fun `keeps all history when it fits within budget`() {
        val history = listOf(turn(1), turn(2), turn(3))
        val input =
            ContextAssemblyInput(
                systemPrompt = listOf(ContentPart.Text("sys")),
                memoryInjection = emptyList(),
                history = history,
                currentInput = listOf(ContentPart.Text("input")),
            )
        val result = ContextAssemblyService.assemble(input, budget = 10, tokensOf = tokensOf)
        assertEquals(history, result.turns)
        assertFalse(result.truncated)
    }

    @Test
    fun `drops oldest turns first when the budget is tight`() {
        val history = listOf(turn(1), turn(2), turn(3))
        // 固定コスト: system(1)+memory(0)+input(1) = 2。budget=3なので履歴に使えるのは1トークン=1ターンのみ。
        val input =
            ContextAssemblyInput(
                systemPrompt = listOf(ContentPart.Text("sys")),
                memoryInjection = emptyList(),
                history = history,
                currentInput = listOf(ContentPart.Text("input")),
            )
        val result = ContextAssemblyService.assemble(input, budget = 3, tokensOf = tokensOf)
        assertEquals(listOf(turn(3)), result.turns)
        assertTrue(result.truncated)
    }

    @Test
    fun `throws when fixed content alone exceeds the budget`() {
        val input =
            ContextAssemblyInput(
                systemPrompt = listOf(ContentPart.Text("a"), ContentPart.Text("b"), ContentPart.Text("c")),
                memoryInjection = emptyList(),
                history = emptyList(),
                currentInput = emptyList(),
            )
        assertThrows(ContextBudgetExceededException::class.java) {
            ContextAssemblyService.assemble(input, budget = 1, tokensOf = tokensOf)
        }
    }
}
