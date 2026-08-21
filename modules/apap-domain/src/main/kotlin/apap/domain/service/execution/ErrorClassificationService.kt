package apap.domain.service.execution

import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.NormalizedError

/**
 * 04_ドメイン設計.md 4.6 / 02_システム仕様.md 2.11: AdapterException→NormalizedError分類。
 * 2.11の表（同一候補Retry可否・Fallback可否）をそのまま実装する。
 */
object ErrorClassificationService {
    private data class ClassificationRule(
        val code: ErrorCode,
        val retryable: Boolean,
        val fallbackable: Boolean,
    )

    fun classify(
        category: AdapterErrorCategory,
        message: String,
        providerDetail: String? = null,
        contentFilteredFallbackAllowed: Boolean = false,
    ): NormalizedError {
        val rule = ruleFor(category, contentFilteredFallbackAllowed)
        return NormalizedError(
            code = rule.code,
            category = category,
            message = message,
            retryable = rule.retryable,
            fallbackable = rule.fallbackable,
            providerDetail = providerDetail,
        )
    }

    private fun ruleFor(
        category: AdapterErrorCategory,
        contentFilteredFallbackAllowed: Boolean,
    ): ClassificationRule =
        when (category) {
            AdapterErrorCategory.TRANSIENT ->
                ClassificationRule(ErrorCode.PROVIDER_ERROR, retryable = true, fallbackable = true)
            AdapterErrorCategory.RATE_LIMITED ->
                ClassificationRule(ErrorCode.RATE_LIMIT_EXCEEDED, retryable = true, fallbackable = true)
            AdapterErrorCategory.INVALID_REQUEST ->
                ClassificationRule(ErrorCode.INVALID_REQUEST, retryable = false, fallbackable = false)
            AdapterErrorCategory.AUTH_ERROR ->
                ClassificationRule(ErrorCode.UNAUTHENTICATED, retryable = false, fallbackable = true)
            AdapterErrorCategory.CONTENT_FILTERED ->
                ClassificationRule(
                    ErrorCode.CONTENT_FILTERED,
                    retryable = false,
                    fallbackable = contentFilteredFallbackAllowed,
                )
            AdapterErrorCategory.MODEL_ERROR ->
                ClassificationRule(ErrorCode.OUTPUT_SCHEMA_VIOLATION, retryable = true, fallbackable = true)
            AdapterErrorCategory.PROVIDER_UNAVAILABLE ->
                ClassificationRule(ErrorCode.PROVIDER_ERROR, retryable = false, fallbackable = true)
            AdapterErrorCategory.UNSUPPORTED_CAPABILITY ->
                ClassificationRule(ErrorCode.CAPABILITY_NOT_AVAILABLE, retryable = false, fallbackable = false)
        }
}
