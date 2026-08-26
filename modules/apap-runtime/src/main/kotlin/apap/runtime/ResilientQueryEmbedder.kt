package apap.runtime

import apap.cache.ratelimit.AcquireResult
import apap.cache.ratelimit.RateLimitScope
import apap.cache.ratelimit.RateLimiter
import apap.context.QueryEmbedder
import apap.domain.model.vo.CbKey
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import apap.execution.circuitbreaker.CircuitBreaker
import apap.execution.circuitbreaker.CircuitOpenException
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * ADR-0023: Memory注入の埋め込み呼出（02_システム仕様.md 2.17、チャット実行のたびに走る高頻度経路）を
 * [delegate]（実Adapter呼出を行う将来の実装、P8以降）の実行前後で、メインリクエストと同じ
 * [CircuitBreaker]/[RateLimiter]へ通す。ExecutionEngineComposerが構築するインスタンスをそのまま
 * 受け取れるため（`apap-runtime`はあらゆるモジュールに依存できるコンポジションルート）、
 * ADR-0022が誤って前提としていた循環依存は生じない。
 *
 * CbKeyは`(providerId, modelId)`のみで構成される（04_ドメイン設計.md 4.4）。埋め込み用の
 * Provider/Modelはメインのchat用Candidateとは通常異なるため、CB状態は追加の仕組みなしに
 * 自然に分離される（同一(provider, model)が両方の用途に使われる特殊ケースでは意図的にCB状態を
 * 共有する——それ自体がそのProvider/Modelの実態を反映しているため、要件充足に影響しない実装判断の
 * ためADR化せずここに根拠を残す）。RateLimiterはテナントスコープとProviderスコープの両方を適用する
 * （ADR-0012のキャッシュ短絡時と異なり、これは実際にProviderへ到達する呼出のため、Providerの
 * レート制限を素通りさせてはならない）。
 *
 * CB/RateLimiterに拒否された場合、または[delegate]自体が失敗した場合は、例外を投げず空ベクトルへ
 * 縮退する（ADR-0022由来の既存方針を維持: Memory注入はベストエフォート、メイン応答を失敗させない）。
 */
@Suppress("LongParameterList")
class ResilientQueryEmbedder(
    private val delegate: QueryEmbedder,
    private val circuitBreaker: CircuitBreaker,
    private val rateLimiter: RateLimiter,
    private val providerId: ProviderId,
    private val modelId: ModelId,
    private val tenantId: TenantId,
    private val traceId: String,
    private val maxWait: Duration = DEFAULT_MAX_WAIT,
) : QueryEmbedder {
    @Suppress("ReturnCount")
    override suspend fun embed(parts: List<ContentPart>): List<Double> {
        if (!acquireRateLimit(RateLimitScope.TenantScope(tenantId))) return degrade("tenant rate limit rejected")
        if (!acquireRateLimit(RateLimitScope.ProviderScope(providerId))) return degrade("provider rate limit rejected")

        val key = CbKey(providerId, modelId)
        val permit =
            try {
                circuitBreaker.tryAcquire(key, traceId)
            } catch (e: CircuitOpenException) {
                return degrade("circuit breaker OPEN: ${e.message}")
            }

        return runCatching { delegate.embed(parts) }
            .onSuccess { circuitBreaker.recordSuccess(permit, traceId) }
            .getOrElse { e ->
                circuitBreaker.recordFailure(permit, cbRecordable = true, traceId)
                degrade("delegate embedding call failed: ${e.message}")
            }
    }

    private suspend fun acquireRateLimit(scope: RateLimitScope): Boolean =
        rateLimiter.acquire(scope, traceId, maxWait) !is AcquireResult.Rejected

    private fun degrade(reason: String): List<Double> {
        logger.warn("embedding call degraded to an empty vector: {}", reason)
        return emptyList()
    }

    private companion object {
        val DEFAULT_MAX_WAIT: Duration = Duration.ofSeconds(2)
        val logger = LoggerFactory.getLogger(ResilientQueryEmbedder::class.java)
    }
}
