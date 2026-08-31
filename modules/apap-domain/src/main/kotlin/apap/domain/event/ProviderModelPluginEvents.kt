package apap.domain.event

import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.SemVer

/**
 * 14_イベント一覧.md 14.1 Provider / Model / Plugin系。
 *
 * ADR-0026: これらのイベントは元々「通知」目的の設計（14章はイベント名・発火元・購読先・用途のみを
 * 規定し、payloadの網羅性までは規定しない）で、Aggregateの全フィールドを運ばない場合、
 * 純粋なイベント再生によるフル状態復元ができなかった。イベント名は14章から一切変えず、
 * 再構築に必要なフィールドのみを追加/拡張してある（[apap.domain.model.provider.apply]等を参照）。
 */
data class ProviderRegistered(
    override val meta: EventMetadata,
    val providerId: ProviderId,
    val name: String,
    val adapterPluginId: String,
    val spiVersion: SemVer,
    val endpoints: List<Endpoint>,
    val authType: String,
    val credentialRefs: List<CredentialRef>,
    val rateLimits: RateLimits,
    val priority: Int,
    val regions: Set<Region>,
    val tags: Set<String> = emptySet(),
) : DomainEvent

/**
 * ADR-0026: [credentialVersion]は昇格（STANDBY→ACTIVE）されたCredentialRefを
 * [apap.domain.model.vo.CredentialRef.version]で特定するために追加した（再構築時、
 * どのCredentialRefがACTIVE化されたか事象からは判別できなかったため）。
 */
data class ProviderValidated(
    override val meta: EventMetadata,
    val providerId: ProviderId,
    val credentialVersion: Int,
) : DomainEvent

data class ProviderEnabled(
    override val meta: EventMetadata,
    val providerId: ProviderId,
    val reason: String,
) : DomainEvent

data class ProviderDraining(
    override val meta: EventMetadata,
    val providerId: ProviderId,
    val reason: String,
) : DomainEvent

data class ProviderDisabled(
    override val meta: EventMetadata,
    val providerId: ProviderId,
    val reason: String,
) : DomainEvent

data class ProviderDeleted(
    override val meta: EventMetadata,
    val providerId: ProviderId,
) : DomainEvent

data class ProviderHealthChanged(
    override val meta: EventMetadata,
    val providerId: ProviderId,
    val from: ProviderHealthStatus,
    val to: ProviderHealthStatus,
    val evidence: String,
) : DomainEvent

data class ModelRegistered(
    override val meta: EventMetadata,
    val modelId: ModelId,
    val providerId: ProviderId,
    val capabilities: List<ModelCapability>,
    val modelName: String,
    val version: String,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val regions: Set<Region>,
    val priority: Int,
) : DomainEvent

data class ModelStatusChanged(
    override val meta: EventMetadata,
    val modelId: ModelId,
    val from: ModelStatus,
    val to: ModelStatus,
) : DomainEvent

data class ModelDiscovered(
    override val meta: EventMetadata,
    val providerId: ProviderId,
    val discoveredModels: List<String>,
) : DomainEvent

data class AliasTargetSnapshot(
    val modelId: ModelId,
    val weight: Int,
)

data class AliasChanged(
    override val meta: EventMetadata,
    val aliasId: String,
    val name: String,
    val oldTargets: List<AliasTargetSnapshot>,
    val newTargets: List<AliasTargetSnapshot>,
) : DomainEvent

data class PluginLoaded(
    override val meta: EventMetadata,
    val pluginId: String,
    val version: String,
) : DomainEvent

data class PluginUnloaded(
    override val meta: EventMetadata,
    val pluginId: String,
) : DomainEvent

data class PluginQuarantined(
    override val meta: EventMetadata,
    val pluginId: String,
    val reason: String,
) : DomainEvent
