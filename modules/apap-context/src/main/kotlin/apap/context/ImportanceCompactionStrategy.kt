package apap.context

import apap.domain.model.conversation.Turn
import apap.domain.model.conversation.TurnRole
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.TokenCount

/** 16_拡張ポイント.md 16.7「圧縮戦略」の②要約圧縮とは別の③重要度スコアによる選択保持。 */
fun interface TurnImportanceScorer {
    fun score(turn: Turn): Double
}

/** 既定のスコア: role別の固定重み（SYSTEM > USER > ASSISTANT > TOOL）。 */
object RoleWeightedTurnImportanceScorer : TurnImportanceScorer {
    override fun score(turn: Turn): Double =
        when (turn.role) {
            TurnRole.SYSTEM -> SYSTEM_WEIGHT
            TurnRole.USER -> USER_WEIGHT
            TurnRole.ASSISTANT -> ASSISTANT_WEIGHT
            TurnRole.TOOL -> TOOL_WEIGHT
        }

    private const val SYSTEM_WEIGHT = 3.0
    private const val USER_WEIGHT = 2.0
    private const val ASSISTANT_WEIGHT = 1.0
    private const val TOOL_WEIGHT = 0.5
}

/**
 * 16_拡張ポイント.md 16.7「圧縮戦略」③重要度スコアによる選択保持。[scorer]のスコア降順に
 * 予算内で貪欲に採用し、結果は元の会話順（seq昇順）へ戻す。
 */
class ImportanceCompactionStrategy(
    private val scorer: TurnImportanceScorer = RoleWeightedTurnImportanceScorer,
) : CompactionStrategy {
    override fun compact(
        turns: List<Turn>,
        budgetTokens: Int,
        tokensOf: (List<ContentPart>) -> TokenCount,
    ): CompactionResult {
        val kept = mutableListOf<Turn>()
        var remaining = budgetTokens
        var truncated = false
        for (turn in turns.sortedByDescending(scorer::score)) {
            val turnTokens = tokensOf(turn.contentParts).value
            if (turnTokens <= remaining) {
                kept.add(turn)
                remaining -= turnTokens
            } else {
                truncated = true
            }
        }
        return CompactionResult(turns = kept.sortedBy { it.seq }, truncated = truncated)
    }
}
