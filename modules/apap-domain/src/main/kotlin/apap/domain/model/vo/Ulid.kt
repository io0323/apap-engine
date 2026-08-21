package apap.domain.model.vo

/**
 * ULID（Crockford Base32、26文字、I/L/O/Uを除く）の形式検証のみを行う。
 * ID生成自体は `apap.domain.port.IdGenerator` を通じて行い、ここでは形式検証のみを担う
 * （04_ドメイン設計.md 4.4「ULID形式、生成後不変」）。
 */
internal object Ulid {
    private val PATTERN = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

    fun isValid(value: String): Boolean = PATTERN.matches(value)
}
