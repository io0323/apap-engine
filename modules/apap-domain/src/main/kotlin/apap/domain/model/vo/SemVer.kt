package apap.domain.model.vo

/**
 * 04_ドメイン設計.md 4.4: SPI/Pluginバージョン。semver準拠、互換判定メソッド保持。
 * 優先順位の比較規則はSemantic Versioning 2.0.0仕様に従う（ビルドメタデータは比較に用いない）。
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
    val build: String? = null,
) : Comparable<SemVer> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) {
            "SemVer components must be non-negative: $major.$minor.$patch"
        }
        preRelease?.let {
            require(PRE_RELEASE_PATTERN.matches(it)) { "Invalid semver pre-release identifiers: $it" }
        }
        build?.let {
            require(BUILD_PATTERN.matches(it)) { "Invalid semver build metadata: $it" }
        }
    }

    /**
     * SPIバージョン互換性: メジャーバージョンが一致すれば互換とみなす
     * （semverの一般的な後方互換規約: メジャー一致は破壊的変更なしを意味する）。
     */
    fun isCompatibleWith(other: SemVer): Boolean = major == other.major

    override fun compareTo(other: SemVer): Int {
        val core = compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)
        if (core != 0) return core
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String {
        val core = "$major.$minor.$patch"
        val withPreRelease = preRelease?.let { "$core-$it" } ?: core
        return build?.let { "$withPreRelease+$it" } ?: withPreRelease
    }

    private fun comparePreRelease(
        left: String?,
        right: String?,
    ): Int =
        when {
            left == null && right == null -> 0
            // preReleaseなしはpreReleaseありより優先順位が高い（例: 1.0.0 > 1.0.0-alpha）
            left == null -> 1
            right == null -> -1
            else -> compareIdentifiers(left.split("."), right.split("."))
        }

    private fun compareIdentifiers(
        left: List<String>,
        right: List<String>,
    ): Int {
        val size = minOf(left.size, right.size)
        for (i in 0 until size) {
            val cmp = compareIdentifier(left[i], right[i])
            if (cmp != 0) return cmp
        }
        return left.size.compareTo(right.size)
    }

    private fun compareIdentifier(
        left: String,
        right: String,
    ): Int {
        val leftNum = left.toLongOrNull()
        val rightNum = right.toLongOrNull()
        return when {
            leftNum != null && rightNum != null -> leftNum.compareTo(rightNum)
            // 数値識別子は英数字識別子より常に優先順位が低い
            leftNum != null -> -1
            rightNum != null -> 1
            else -> left.compareTo(right)
        }
    }

    companion object {
        private val CORE_PATTERN =
            Regex(
                """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""" +
                    """(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?""" +
                    """(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$""",
            )
        private val PRE_RELEASE_PATTERN = Regex("^[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*$")
        private val BUILD_PATTERN = Regex("^[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*$")

        // CORE_PATTERNの捕捉グループ番号（groupValues[0]は全体マッチ）
        private const val MAJOR_GROUP = 1
        private const val MINOR_GROUP = 2
        private const val PATCH_GROUP = 3
        private const val PRE_RELEASE_GROUP = 4
        private const val BUILD_GROUP = 5

        fun parse(value: String): SemVer {
            val match = CORE_PATTERN.matchEntire(value) ?: throw IllegalArgumentException("Invalid semver: $value")
            val groups = match.groupValues
            return SemVer(
                major = groups[MAJOR_GROUP].toInt(),
                minor = groups[MINOR_GROUP].toInt(),
                patch = groups[PATCH_GROUP].toInt(),
                preRelease = groups[PRE_RELEASE_GROUP].ifEmpty { null },
                build = groups[BUILD_GROUP].ifEmpty { null },
            )
        }
    }
}
