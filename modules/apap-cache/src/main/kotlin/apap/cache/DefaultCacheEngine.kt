package apap.cache

import apap.domain.event.AliasChanged
import apap.domain.event.DomainEvent
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.CanonicalResponse
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.AliasId
import apap.domain.port.AliasRepository
import apap.domain.port.Clock

/**
 * [CacheEngine]の既定実装。02_システム仕様.md 2.14:
 * - Request Cache: `request.idempotencyKey`があれば常に照会・保存する（[CacheabilityPolicy]は問わない、
 *   冪等性は決定性とは別軸のため）。
 * - Response Cache: [CacheabilityPolicy.isCacheable]がtrueの要求のみ照会・保存する。
 *
 * Response Cacheのキーは`request.modelAlias`（Aliasの名前文字列）をそのまま使わず、
 * [AliasRepository.findByName]で解決した[apap.domain.model.modelcatalog.ModelAlias.aliasId]を使う
 * （[AliasRepository]に逆引き（id→name）が無いため、`AliasChanged`が運ぶ`aliasId`だけで
 * [invalidateByAlias]の`scanByPrefix`が機能するようにする設計、要件充足に影響しない実装判断のため
 * ADR化せず根拠をここに残す）。Alias未指定または未登録の要求は[ResponseCacheKeys.NO_ALIAS_SENTINEL]
 * を使う。
 */
class DefaultCacheEngine(
    private val cacheStore: CacheStore,
    private val cacheKeyStrategy: CacheKeyStrategy,
    private val cacheabilityPolicy: CacheabilityPolicy,
    private val config: CacheConfig,
    private val aliasRepository: AliasRepository,
    private val clock: Clock,
) : CacheEngine {
    override fun lookup(
        request: CanonicalRequest,
        prompt: ProcessedPrompt,
    ): CanonicalResponse? {
        val requestCacheHit =
            request.idempotencyKey?.let { key ->
                cacheStore.get(cacheKeyStrategy.requestCacheKey(request.tenantId, key))
            }
        val responseCacheHit =
            if (cacheabilityPolicy.isCacheable(request)) cacheStore.get(responseCacheKey(request)) else null
        return (requestCacheHit ?: responseCacheHit)?.let { withCacheMetadata(it) }
    }

    override fun store(
        request: CanonicalRequest,
        prompt: ProcessedPrompt,
        response: CanonicalResponse,
    ) {
        val idempotencyKey = request.idempotencyKey
        if (idempotencyKey != null) {
            val requestCacheKey = cacheKeyStrategy.requestCacheKey(request.tenantId, idempotencyKey)
            cacheStore.put(requestCacheKey, response, config.requestCacheTtl)
        }
        if (!cacheabilityPolicy.isCacheable(request)) return
        val responseCacheKey = responseCacheKey(request)
        cacheStore.put(responseCacheKey, response, config.responseCacheTtlFor(request.capabilityId))
    }

    override fun invalidateByAlias(aliasId: AliasId) {
        cacheStore.scanByPrefix(ResponseCacheKeys.aliasPrefix(aliasId.value)).forEach(cacheStore::delete)
    }

    /**
     * イベント購読側（apap-runtime構成ルート、[apap.routing.RoutingCandidateCache.apply]と同じ配線方式）
     * から呼ばれる。`AliasChanged`以外は無視する。
     */
    fun apply(event: DomainEvent) {
        if (event is AliasChanged) {
            invalidateByAlias(AliasId(event.aliasId))
        }
    }

    private fun responseCacheKey(request: CanonicalRequest): String {
        val resolvedAliasId =
            request.modelAlias?.let { name -> aliasRepository.findByName(request.tenantId, name)?.aliasId?.value }
        return cacheKeyStrategy.responseCacheKey(request.tenantId, request.capabilityId, resolvedAliasId, request)
    }

    private fun withCacheMetadata(response: CanonicalResponse): CanonicalResponse =
        response.copy(
            cached = true,
            metadata = response.metadata + (CACHED_AT_METADATA_KEY to clock.now().toString()),
        )

    private companion object {
        const val CACHED_AT_METADATA_KEY = "cached_at"
    }
}
