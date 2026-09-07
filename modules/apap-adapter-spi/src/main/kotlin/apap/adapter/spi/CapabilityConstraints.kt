package apap.adapter.spi

/** 03_基本設計.md 3.3.2 `capabilityConstraints(capabilityId): CapabilityConstraints`。 */
data class CapabilityConstraints(
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val streamable: Boolean = false,
    val supportsTools: Boolean = false,
    val extra: Map<String, String> = emptyMap(),
    /**
     * 受け付けられる入力modality。**空集合は「未申告」**であり「非対応」ではない
     * （既存Adapterを無改修で通すため。ADR-0039）。申告があるAdapterに対してのみ
     * Routingがハードフィルタを掛ける。
     *
     * 既存の位置引数呼出を壊さないよう**末尾**に置く（ADR-0016: 省略可能フィールドの追加は
     * マイナー更新に留まるが、順序を変えると破壊的変更になる）。
     */
    val supportedInputModalities: Set<Modality> = emptySet(),
) {
    init {
        maxInputTokens?.let { require(it > 0) { "maxInputTokens must be positive: $it" } }
        maxOutputTokens?.let { require(it > 0) { "maxOutputTokens must be positive: $it" } }
    }
}
