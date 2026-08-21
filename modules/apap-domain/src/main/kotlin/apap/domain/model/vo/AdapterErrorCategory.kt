package apap.domain.model.vo

/**
 * CLAUDE.md 実装規約 / 02_システム仕様.md 2.11: AdapterExceptionの分類。
 * この分類がRetry/Fallback/CircuitBreaker記録の可否を決める（2.11の表が仕様）。
 */
enum class AdapterErrorCategory {
    TRANSIENT,
    RATE_LIMITED,
    INVALID_REQUEST,
    AUTH_ERROR,
    CONTENT_FILTERED,
    MODEL_ERROR,
    PROVIDER_UNAVAILABLE,
    UNSUPPORTED_CAPABILITY,
}
