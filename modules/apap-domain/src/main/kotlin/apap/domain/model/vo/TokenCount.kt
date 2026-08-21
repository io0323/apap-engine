package apap.domain.model.vo

/** 04_ドメイン設計.md 4.4: 非負整数。 */
data class TokenCount(
    val value: Int,
) {
    init {
        require(value >= 0) { "TokenCount must not be negative: $value" }
    }

    operator fun plus(other: TokenCount): TokenCount = TokenCount(value + other.value)

    companion object {
        val ZERO = TokenCount(0)
    }
}
