package apap.adapter.spi

/**
 * 03_基本設計.md 3.3.1 CanonicalRequest.params（GenerationParams）を、
 * Adapter層（[AdapterRequest]）向けにそのまま踏襲したもの。
 */
data class GenerationParams(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val stop: List<String> = emptyList(),
    val seed: Long? = null,
)
