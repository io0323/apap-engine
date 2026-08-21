package apap.adapter.spi.plugin

import apap.domain.model.vo.SemVer

/**
 * 15_Provider追加手順.md 15.1 `plugin.yaml`の `spi_version` フィールド（例: `">=1.2 <2.0"`）。
 * 比較子(`>=` `<=` `>` `<` `=`)をスペース区切りで並べたANDレンジをパースする。
 */
data class SemVerRange(
    val comparators: List<Comparator>,
) {
    enum class Op(
        val symbol: String,
    ) {
        GTE(">="),
        LTE("<="),
        GT(">"),
        LT("<"),
        EQ("="),
    }

    data class Comparator(
        val op: Op,
        val version: SemVer,
    ) {
        fun matches(candidate: SemVer): Boolean =
            when (op) {
                Op.GTE -> candidate >= version
                Op.LTE -> candidate <= version
                Op.GT -> candidate > version
                Op.LT -> candidate < version
                Op.EQ -> candidate == version
            }
    }

    init {
        require(comparators.isNotEmpty()) { "SemVerRange must have at least one comparator" }
    }

    fun contains(version: SemVer): Boolean = comparators.all { it.matches(version) }

    companion object {
        // 長い記号("<="/">=")を短い記号("<"/">")より先に試すこと（先頭一致の誤判定を避けるため）。
        private val OPS_BY_LENGTH_DESC = Op.entries.sortedByDescending { it.symbol.length }

        fun parse(value: String): SemVerRange {
            val comparators =
                value
                    .trim()
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .map { parseComparator(it) }
            return SemVerRange(comparators)
        }

        private fun parseComparator(token: String): Comparator {
            val op =
                OPS_BY_LENGTH_DESC.firstOrNull { token.startsWith(it.symbol) }
                    ?: throw IllegalArgumentException("Invalid spi_version comparator (missing operator): $token")
            val versionText = token.removePrefix(op.symbol)
            return Comparator(op, SemVer.parse(padToFullCore(versionText)))
        }

        /**
         * 15.1の`spi_version`例（`">=1.2 <2.0"`）は`major.minor`の短縮形を使う。
         * `SemVer.parse`はSemVer 2.0.0仕様通り`major.minor.patch`の完全形のみを受理するため、
         * レンジの比較子に限り、欠けた`minor`/`patch`を`0`で補ってから委譲する
         * （CLAUDE.md「ADR化するか否かの判断基準」: 設計書の例をそのまま解釈可能にする表記上の吸収であり、
         * FR/NFRの充足には影響しない実装詳細）。
         */
        private fun padToFullCore(versionText: String): String {
            val preReleaseSeparator = versionText.indexOfFirst { it == '-' || it == '+' }
            val core = if (preReleaseSeparator >= 0) versionText.substring(0, preReleaseSeparator) else versionText
            val suffix = if (preReleaseSeparator >= 0) versionText.substring(preReleaseSeparator) else ""
            val components = core.split(".")
            val padded = (components + List(FULL_CORE_COMPONENT_COUNT) { "0" }).take(FULL_CORE_COMPONENT_COUNT)
            return padded.joinToString(".") + suffix
        }

        private const val FULL_CORE_COMPONENT_COUNT = 3
    }
}
