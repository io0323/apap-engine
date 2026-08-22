package apap.domain.service.execution

import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TokenCount
import kotlin.math.ceil

/** ADR-0009: EXACT/HEURISTICの2値。安全マージンはモード連動。 */
enum class TokenEstimationMode { EXACT, HEURISTIC }

/**
 * ADR-0009: 安全マージンはモード連動（EXACT→5%既定、HEURISTIC→15%既定、いずれも設定可）。
 * ヒューリスティックのモデル別文字/トークン比も設定可能とする。
 */
data class TokenEstimationConfig(
    val exactSafetyMarginRatio: Double = 0.05,
    val heuristicSafetyMarginRatio: Double = 0.15,
    val charsPerTokenByModel: Map<ModelId, Double> = emptyMap(),
    val defaultCharsPerToken: Double = 4.0,
) {
    init {
        require(exactSafetyMarginRatio >= 0.0) { "exactSafetyMarginRatio must not be negative" }
        require(heuristicSafetyMarginRatio >= 0.0) { "heuristicSafetyMarginRatio must not be negative" }
        require(defaultCharsPerToken > 0.0) { "defaultCharsPerToken must be positive" }
        charsPerTokenByModel.values.forEach { require(it > 0.0) { "charsPerToken ratio must be positive: $it" } }
    }
}

/**
 * 04_ドメイン設計.md 4.6 / ADR-0009: Model別Tokenizer抽象によるトークン推定。
 * EXACTモードの実トークナイザー呼出はAdapter SPI（ADR-0010）越しのI/Oのため、
 * 呼び出し側があらかじめ計測した生トークン数を`exactRawTokenCount`として供給する
 * （本Serviceはマージン適用・切り上げのみを行う純粋関数）。
 */
object TokenEstimationService {
    /**
     * 安全マージン適用前の生ヒューリスティック推定値。apap-context（`ContextTokenCounter`）が
     * apap-execution/apap-providerへ依存せずにHEURISTICモードのトークン計上を再利用するために
     * 公開する（apap-contextはapap-domainのみに依存でき、Adapter越しのEXACT推定へは到達できない
     * 依存方向のため）。
     */
    fun rawHeuristicTokenCount(
        text: String,
        modelId: ModelId,
        config: TokenEstimationConfig,
    ): Int {
        val ratio = config.charsPerTokenByModel[modelId] ?: config.defaultCharsPerToken
        return ceil(text.length / ratio).toInt()
    }

    fun estimateHeuristic(
        text: String,
        modelId: ModelId,
        config: TokenEstimationConfig,
    ): TokenCount = applyMargin(rawHeuristicTokenCount(text, modelId, config), config.heuristicSafetyMarginRatio)

    fun estimateExact(
        exactRawTokenCount: Int,
        config: TokenEstimationConfig,
    ): TokenCount {
        require(exactRawTokenCount >= 0) { "exactRawTokenCount must not be negative: $exactRawTokenCount" }
        return applyMargin(exactRawTokenCount, config.exactSafetyMarginRatio)
    }

    fun safetyMarginFor(
        mode: TokenEstimationMode,
        config: TokenEstimationConfig,
    ): Double =
        when (mode) {
            TokenEstimationMode.EXACT -> config.exactSafetyMarginRatio
            TokenEstimationMode.HEURISTIC -> config.heuristicSafetyMarginRatio
        }

    /** 安全マージンを適用し、常に切り上げる。 */
    private fun applyMargin(
        rawTokenCount: Int,
        marginRatio: Double,
    ): TokenCount {
        val withMargin = ceil(rawTokenCount * (1.0 + marginRatio)).toInt()
        return TokenCount(withMargin)
    }
}
