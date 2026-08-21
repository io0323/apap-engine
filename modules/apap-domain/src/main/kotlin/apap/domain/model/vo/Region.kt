package apap.domain.model.vo

/**
 * ADR-0006: Region内部コード表の設定駆動化。有効コード一覧を保持するだけの値であり、
 * 実際の読込（設定ファイル等からのロード）はInfrastructure層の責務。テストでは
 * 任意の `RegionCodeTable` を差し替えて検証できる。
 */
data class RegionCodeTable(
    val validCodes: Set<String>,
) {
    fun contains(code: String): Boolean = code in validCodes
}

/**
 * 04_ドメイン設計.md 4.4: リージョンコード。ADR-0006により、コードをenumでハードコードせず
 * `RegionCodeTable` に対する生成時検証で未知コードを例外とする。
 */
@ConsistentCopyVisibility
data class Region private constructor(
    val code: String,
) {
    companion object {
        fun of(
            code: String,
            table: RegionCodeTable,
        ): Region {
            require(table.contains(code)) { "Unknown region code (not present in configured RegionCodeTable): $code" }
            return Region(code)
        }
    }
}
