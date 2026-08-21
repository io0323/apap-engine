package apap.domain.model.vo

/**
 * 04_ドメイン設計.md 4.4 / 02_システム仕様.md 2.9: inputTokens, outputTokens, totalTokens, estimated。
 * total = in + out。cachedTokens / reasoningTokens は2.9の任意項目。
 */
data class Usage(
    val inputTokens: TokenCount,
    val outputTokens: TokenCount,
    val totalTokens: TokenCount,
    val estimated: Boolean = false,
    val cachedTokens: TokenCount? = null,
    val reasoningTokens: TokenCount? = null,
) {
    init {
        require(totalTokens == inputTokens + outputTokens) {
            "Usage.totalTokens must equal inputTokens + outputTokens: " +
                "total=$totalTokens, input=$inputTokens, output=$outputTokens"
        }
    }

    companion object {
        fun of(
            inputTokens: TokenCount,
            outputTokens: TokenCount,
            estimated: Boolean = false,
            cachedTokens: TokenCount? = null,
            reasoningTokens: TokenCount? = null,
        ): Usage =
            Usage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = inputTokens + outputTokens,
                estimated = estimated,
                cachedTokens = cachedTokens,
                reasoningTokens = reasoningTokens,
            )
    }
}
