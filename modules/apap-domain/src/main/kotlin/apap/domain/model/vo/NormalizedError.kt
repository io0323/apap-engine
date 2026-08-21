package apap.domain.model.vo

/**
 * 04_ドメイン設計.md 4.4: code, category, message, retryable, fallbackable, providerDetail?。
 * codeは13章のエラー体系（[ErrorCode]）。categoryはAdapterException分類（[AdapterErrorCategory]）。
 *
 * [cbRecordable] は02_システム仕様.md 2.11「CB記録」列の**カテゴリ単位の基本可否**を表す
 * （4.4の代表フィールド一覧にはないが、2.11がRetry/Fallbackと並置している同一表の一部であり、
 * 2.13のCircuit Breaker実装（apap-execution）がこの表を再実装せず参照できるようにするための
 * フィールド追加。RATE_LIMITEDの「△（連続時のみ）」は本フィールドではtrueとし、
 * 「直前も同一カテゴリで失敗した場合のみ実際に記録する」という文脈依存の絞り込みは
 * 状態を持つCircuitBreaker（apap-execution）側の責務とする）。
 */
data class NormalizedError(
    val code: ErrorCode,
    val category: AdapterErrorCategory,
    val message: String,
    val retryable: Boolean,
    val fallbackable: Boolean,
    val cbRecordable: Boolean,
    val providerDetail: String? = null,
) {
    init {
        require(message.isNotBlank()) { "NormalizedError.message must not be blank" }
    }
}
