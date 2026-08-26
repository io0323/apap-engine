package apap.cache

import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.CanonicalResponse
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.AliasId

/**
 * 03_基本設計.md 3.3.6 `CacheEngine` / 02_システム仕様.md 2.14。Request Cache（冪等キー）・
 * Response Cache（決定的要求）の双方を担う。既定実装は[DefaultCacheEngine]。
 */
interface CacheEngine {
    fun lookup(
        request: CanonicalRequest,
        prompt: ProcessedPrompt,
    ): CanonicalResponse?

    fun store(
        request: CanonicalRequest,
        prompt: ProcessedPrompt,
        response: CanonicalResponse,
    )

    fun invalidateByAlias(aliasId: AliasId)
}
