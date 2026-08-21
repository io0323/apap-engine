package apap.domain.model.vo

/**
 * 04_ドメイン設計.md 4.4: code, category, message, retryable, fallbackable, providerDetail?。
 * codeは13章のエラー体系（[ErrorCode]）。categoryはAdapterException分類（[AdapterErrorCategory]）。
 */
data class NormalizedError(
    val code: ErrorCode,
    val category: AdapterErrorCategory,
    val message: String,
    val retryable: Boolean,
    val fallbackable: Boolean,
    val providerDetail: String? = null,
) {
    init {
        require(message.isNotBlank()) { "NormalizedError.message must not be blank" }
    }
}
