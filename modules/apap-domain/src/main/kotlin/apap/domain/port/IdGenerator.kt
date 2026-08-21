package apap.domain.port

/**
 * CLAUDE.md 実装規約: ID生成はPort化して注入する（`UUID.randomUUID()`の直接呼び出しを
 * domain内で禁止）。実装はULID等を生成する（各型付きID VOの形式検証に適合する値）。
 */
interface IdGenerator {
    fun newId(): String
}
