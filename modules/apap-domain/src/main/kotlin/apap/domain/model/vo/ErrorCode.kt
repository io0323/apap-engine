package apap.domain.model.vo

/**
 * 13_API設計.md 13.4: 共通エラーコード体系。
 * 各値のHTTPステータスコードは`httpStatus`という名前が既に意味を示すため、MagicNumber指摘は抑制する。
 */
@Suppress("MagicNumber")
enum class ErrorCode(
    val httpStatus: Int,
    val retryableByDefault: Boolean,
) {
    INVALID_REQUEST(400, false),
    PROMPT_VALIDATION_FAILED(400, false),
    UNAUTHENTICATED(401, false),
    PERMISSION_DENIED(403, false),
    CAPABILITY_NOT_AVAILABLE(404, false),
    ALIAS_NOT_FOUND(404, false),
    CONVERSATION_NOT_FOUND(404, false),
    CONFLICT(409, false),
    PAYLOAD_TOO_LARGE(413, false),
    CONTEXT_LENGTH_EXCEEDED(422, false),
    OUTPUT_SCHEMA_VIOLATION(422, false),
    CONTENT_FILTERED(422, false),
    RATE_LIMIT_EXCEEDED(429, true),
    QUOTA_EXCEEDED(429, false),
    INTERNAL_ERROR(500, true),
    PROVIDER_ERROR(502, true),
    NO_CANDIDATE_AVAILABLE(503, true),
    TIMEOUT(504, true),
}
