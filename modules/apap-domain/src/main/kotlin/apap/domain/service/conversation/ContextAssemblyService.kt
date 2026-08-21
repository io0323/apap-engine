package apap.domain.service.conversation

import apap.domain.model.conversation.Turn
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.TokenCount
import kotlin.math.ceil

class ContextBudgetExceededException(
    required: Int,
    budget: Int,
) : RuntimeException(
        "Fixed context content ($required tokens: system prompt + memory + current input) exceeds " +
            "the available token budget ($budget tokens) even after dropping all history turns",
    )

/**
 * 02_システム仕様.md 2.16: (1)System Prompt (2)Memory注入分 (3)履歴 (4)今回入力 を合成し、
 * `contextWindow - maxOutputTokens - 安全マージン` に収まるよう圧縮する。
 * 圧縮戦略は既定「①古いTurnから切詰め」を実装する（②要約圧縮 / ③重要度スコアはSPI差替可のため
 * ここでは対象外）。
 */
data class AssembledContext(
    val systemPrompt: List<ContentPart>,
    val memoryInjection: List<ContentPart>,
    val turns: List<Turn>,
    val currentInput: List<ContentPart>,
    val estimatedTokens: TokenCount,
    val truncated: Boolean,
)

/** [ContextAssemblyService.assemble] への入力素材（合成前の未圧縮の各要素）。 */
data class ContextAssemblyInput(
    val systemPrompt: List<ContentPart>,
    val memoryInjection: List<ContentPart>,
    val history: List<Turn>,
    val currentInput: List<ContentPart>,
)

object ContextAssemblyService {
    fun computeBudget(
        contextWindow: Int,
        maxOutputTokens: Int,
        safetyMarginRatio: Double,
    ): Int {
        require(contextWindow > 0) { "contextWindow must be positive: $contextWindow" }
        require(maxOutputTokens in 0 until contextWindow) {
            "maxOutputTokens must be within 0 until contextWindow: $maxOutputTokens, $contextWindow"
        }
        require(safetyMarginRatio >= 0.0) { "safetyMarginRatio must not be negative: $safetyMarginRatio" }
        val margin = ceil(contextWindow * safetyMarginRatio).toInt()
        return contextWindow - maxOutputTokens - margin
    }

    /**
     * @param tokensOf 呼び出し側が供給するトークン数見積り関数（[apap.domain.service.execution.TokenEstimationService]を
     *                 ラップしたもの）。
     */
    fun assemble(
        input: ContextAssemblyInput,
        budget: Int,
        tokensOf: (List<ContentPart>) -> TokenCount,
    ): AssembledContext {
        val systemPromptTokens = tokensOf(input.systemPrompt).value
        val memoryTokens = tokensOf(input.memoryInjection).value
        val currentInputTokens = tokensOf(input.currentInput).value
        val fixedTokens = systemPromptTokens + memoryTokens + currentInputTokens
        if (fixedTokens > budget) {
            throw ContextBudgetExceededException(fixedTokens, budget)
        }

        var remaining = budget - fixedTokens
        val keptTurnsNewestFirst = mutableListOf<Turn>()
        var truncated = false
        for (turn in input.history.asReversed()) {
            val turnTokens = tokensOf(turn.contentParts).value
            if (turnTokens <= remaining) {
                keptTurnsNewestFirst.add(turn)
                remaining -= turnTokens
            } else {
                truncated = true
            }
        }
        val keptTurns = keptTurnsNewestFirst.reversed()

        return AssembledContext(
            systemPrompt = input.systemPrompt,
            memoryInjection = input.memoryInjection,
            turns = keptTurns,
            currentInput = input.currentInput,
            estimatedTokens = TokenCount(budget - remaining),
            truncated = truncated,
        )
    }
}
