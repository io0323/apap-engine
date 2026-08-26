package apap.observability.audit

/**
 * 16_拡張ポイント.md 16.6「マスキング」SPI。Audit本文保存opt-in時に、保存前のテキストへ適用する。
 *
 * 重要: 正規表現ベースの実装（[RegexMaskingStrategy]含む）は完全なPIIマスキングを保証しない。
 * 詳細と運用上の注意は `docs/observability/masking.md` を参照すること。本SPI自体はマスキング
 * アルゴリズムを規定しない（差替可能）。
 */
fun interface MaskingStrategy {
    fun mask(text: String): String
}

/**
 * 汎用パターンによる既定実装（メール/電話/クレジットカード番号/IPv4）。[docs/observability/masking.md]
 * に明記の通り、これは網羅的なPII検出ではなく、コンプライアンス要件充足の主張には使えない。
 */
class RegexMaskingStrategy(
    private val patterns: List<Regex> = DEFAULT_PATTERNS,
    private val replacement: String = "[MASKED]",
) : MaskingStrategy {
    override fun mask(text: String): String = patterns.fold(text) { acc, pattern -> pattern.replace(acc, replacement) }

    companion object {
        val EMAIL: Regex = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        val PHONE: Regex = Regex("""(?<!\d)(\+?\d{1,3}[- ]?)?(\(?\d{2,4}\)?[- ]?)?\d{2,4}[- ]?\d{3,4}(?!\d)""")
        val CREDIT_CARD: Regex = Regex("""\b\d(?:[ -]?\d){12,18}\b""")
        val IPV4: Regex = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
        val DEFAULT_PATTERNS: List<Regex> = listOf(EMAIL, CREDIT_CARD, IPV4, PHONE)
    }
}
