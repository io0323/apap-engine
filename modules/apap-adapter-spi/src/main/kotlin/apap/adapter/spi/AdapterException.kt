package apap.adapter.spi

import apap.domain.model.vo.AdapterErrorCategory
import java.time.Duration

/**
 * CLAUDE.md 実装規約 / 02_システム仕様.md 2.11: Adapterは必ずこの例外で分類済みエラーを送出する。
 * [category] がRetry/Fallback/CircuitBreaker記録の可否を決める。コア側は
 * [apap.domain.service.execution.ErrorClassificationService] でこの分類を[apap.domain.model.vo.NormalizedError]へ
 * 正規化する。
 *
 * 2.11の対応表（同一候補Retry可否 / Fallback可否 / CB記録可否）:
 *
 * | 分類 | 同一候補Retry | Fallback | CB記録 |
 * |---|---|---|---|
 * | TRANSIENT（ネットワーク断、5xx、timeout） | ○ | ○ | ○ |
 * | RATE_LIMITED（429） | ○（Retry-After準拠） | ○ | △（連続時のみ） |
 * | INVALID_REQUEST（4xxバリデーション） | × | × | × |
 * | AUTH_ERROR（401/403） | ×（Credential検証を起動） | ○ | ○ |
 * | CONTENT_FILTERED（セーフティ拒否） | × | ×（既定。Policyで○可） | × |
 * | MODEL_ERROR（出力Schema違反） | ○（是正プロンプトで最大2回） | ○ | × |
 * | PROVIDER_UNAVAILABLE（503、CB Open） | × | ○ | ○ |
 * | UNSUPPORTED_CAPABILITY | × | × | × |
 *
 * 実装規約: Provider固有のエラーメッセージ・Credential値を[message]や[providerDetail]へ混入させないこと
 * （01_CLAUDE.md 不変条件4）。
 */
class AdapterException(
    val category: AdapterErrorCategory,
    message: String,
    val retryAfter: Duration? = null,
    val providerDetail: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
