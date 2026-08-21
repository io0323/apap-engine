package apap.cache

import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.CanonicalResponse
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.AliasId
import org.slf4j.LoggerFactory

/**
 * 03_基本設計.md 3.3.6 `CacheEngine` / 02_システム仕様.md 2.14。Request Cache（冪等キー）・
 * Response Cache（決定的要求）の双方を担う。本フェーズ（実行エンジン）はCache Engine自体（P7）を
 * 対象外とし、apap-executionが依存する口としてこのPortのみを定義する。
 */
interface CacheEngine {
    val isStub: Boolean get() = false

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

/**
 * P7未着手のためのパススルー実装: 常にmiss（`lookup`はnull）、`store`/`invalidateByAlias`はno-op。
 * [optedIn]の明示的な`true`指定を必須とし、構築時にWARNログを出す
 * （`apap.prompt.PassthroughPromptEngine`と同じ方針）。
 */
class PassthroughCacheEngine(
    optedIn: Boolean,
) : CacheEngine {
    override val isStub: Boolean = true

    init {
        require(optedIn) {
            "PassthroughCacheEngine requires explicit opt-in (optedIn=true). " +
                "Request/Response Cache (P7) is not implemented; every request reaches the Provider."
        }
        logger.warn(
            "PassthroughCacheEngine is wired in — Request/Response Cache (P7) is not implemented. " +
                "lookup() always misses, store()/invalidateByAlias() are no-ops.",
        )
    }

    override fun lookup(
        request: CanonicalRequest,
        prompt: ProcessedPrompt,
    ): CanonicalResponse? = null

    override fun store(
        request: CanonicalRequest,
        prompt: ProcessedPrompt,
        response: CanonicalResponse,
    ) = Unit

    override fun invalidateByAlias(aliasId: AliasId) = Unit

    private companion object {
        val logger = LoggerFactory.getLogger(PassthroughCacheEngine::class.java)
    }
}
