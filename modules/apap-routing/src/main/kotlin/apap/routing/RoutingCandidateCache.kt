package apap.routing

import apap.domain.event.DomainEvent
import apap.domain.event.ModelRegistered
import apap.domain.event.ModelStatusChanged
import apap.domain.event.ProviderDeleted
import apap.domain.event.ProviderDisabled
import apap.domain.event.ProviderDraining
import apap.domain.event.ProviderEnabled
import apap.domain.event.ProviderHealthChanged
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId

/**
 * 02_システム仕様.md 2.2.1 CQRS: 「参照系はイベントから構築したRead Modelを使用する」をRouting候補向けに
 * 適用したIn-Memory Read Model。[apply]はイベント購読側から呼ばれ、`event.meta.eventId`で冪等
 * （同一eventIdの再配送は無視する、at-least-once配送を前提とするEvent Busの仕様に対応）。
 *
 * キャッシュが保持するのはProvider状態・Health・Model状態のみ。Alias（targets）とPolicyは
 * `AliasChanged`/`PolicyUpdated`のペイロードだけでは実データを再構築できない
 * （前者はaliasIdのみ、後者はpolicyId/scopeのみを運ぶ）ため、実データの二重管理を避けて
 * `AliasRepository`/`PolicyRepository`から常に最新を読む設計とし、本キャッシュは
 * 「購読して冪等に処理した」事実の記録のみを行う（[CandidateFactory]は常にRepositoryを直接参照する）。
 *
 * まだこのキャッシュがイベントを一度も観測していないProvider/Modelについては`null`を返す。
 * 呼び出し側（[CandidateFactory]）はその場合Repositoryへフォールバックする。
 */
class RoutingCandidateCache {
    private val processedEventIds = mutableSetOf<String>()
    private val providerStatuses = mutableMapOf<ProviderId, ProviderStatus>()
    private val providerHealth = mutableMapOf<ProviderId, ProviderHealthStatus>()
    private val modelStatuses = mutableMapOf<ModelId, ModelStatus>()

    fun apply(event: DomainEvent) {
        if (!processedEventIds.add(event.meta.eventId)) return
        when (event) {
            is ProviderEnabled -> providerStatuses[event.providerId] = ProviderStatus.ACTIVE
            is ProviderDraining -> providerStatuses[event.providerId] = ProviderStatus.DRAINING
            is ProviderDisabled -> providerStatuses[event.providerId] = ProviderStatus.DISABLED
            is ProviderDeleted -> providerStatuses[event.providerId] = ProviderStatus.DELETED
            is ProviderHealthChanged -> providerHealth[event.providerId] = event.to
            is ModelRegistered -> modelStatuses[event.modelId] = ModelStatus.REGISTERED
            is ModelStatusChanged -> modelStatuses[event.modelId] = event.to
            else -> Unit
        }
    }

    fun providerStatus(providerId: ProviderId): ProviderStatus? = providerStatuses[providerId]

    fun providerHealth(providerId: ProviderId): ProviderHealthStatus? = providerHealth[providerId]

    fun modelStatus(modelId: ModelId): ModelStatus? = modelStatuses[modelId]
}
