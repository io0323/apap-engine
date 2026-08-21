package apap.domain.model.vo

/** 02_システム仕様.md 2.9: FinishReason正規化の6値。 */
enum class FinishReason {
    COMPLETED,
    LENGTH_LIMIT,
    TOOL_CALL,
    CONTENT_FILTERED,
    CANCELLED,
    ERROR,
}
