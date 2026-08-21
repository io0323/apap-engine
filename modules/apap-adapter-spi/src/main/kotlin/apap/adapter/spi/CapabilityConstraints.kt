package apap.adapter.spi

/** 03_基本設計.md 3.3.2 `capabilityConstraints(capabilityId): CapabilityConstraints`。 */
data class CapabilityConstraints(
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val streamable: Boolean = false,
    val supportsTools: Boolean = false,
    val extra: Map<String, String> = emptyMap(),
) {
    init {
        maxInputTokens?.let { require(it > 0) { "maxInputTokens must be positive: $it" } }
        maxOutputTokens?.let { require(it > 0) { "maxOutputTokens must be positive: $it" } }
    }
}
