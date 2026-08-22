package apap.context

import apap.domain.model.conversation.Turn
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.TokenCount

data class CompactionResult(
    val turns: List<Turn>,
    val truncated: Boolean,
)

/**
 * 16_拡張ポイント.md 16.7「圧縮戦略」/ 02_システム仕様.md 2.16。[budgetTokens]は履歴専用の
 * 残り予算（System Prompt/Memory/今回入力を差し引いた後の値、[DefaultContextManager]が計算する）。
 * [tokensOf]はマージン適用前の生カウントを返す関数（[ContextTokenCounter.count]をラップしたもの）。
 */
interface CompactionStrategy {
    fun compact(
        turns: List<Turn>,
        budgetTokens: Int,
        tokensOf: (List<ContentPart>) -> TokenCount,
    ): CompactionResult
}
