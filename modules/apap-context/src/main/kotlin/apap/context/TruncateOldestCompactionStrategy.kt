package apap.context

import apap.domain.model.conversation.Turn
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.TokenCount
import apap.domain.service.conversation.ContextAssemblyInput
import apap.domain.service.conversation.ContextAssemblyService

/**
 * 02_システム仕様.md 2.16既定の圧縮戦略「古いTurnから切詰め」。
 * 既存の[ContextAssemblyService.assemble]（古いTurnから切詰める純粋関数、既にテスト済み）へ
 * System Prompt/Memory/今回入力を空にして委譲するだけの薄いラッパーとする（再実装しない）。
 */
class TruncateOldestCompactionStrategy : CompactionStrategy {
    override fun compact(
        turns: List<Turn>,
        budgetTokens: Int,
        tokensOf: (List<ContentPart>) -> TokenCount,
    ): CompactionResult {
        val input =
            ContextAssemblyInput(
                systemPrompt = emptyList(),
                memoryInjection = emptyList(),
                history = turns,
                currentInput = emptyList(),
            )
        val assembled = ContextAssemblyService.assemble(input, budgetTokens, tokensOf)
        return CompactionResult(turns = assembled.turns, truncated = assembled.truncated)
    }
}
