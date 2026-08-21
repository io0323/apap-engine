package apap.domain.event

import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId

/** 14_イベント一覧.md 14.1 Provider / Model / Plugin系。 */
data class ProviderRegistered(
    override val meta: EventMetadata,
    val providerId: ProviderId,
    val name: String,
    val adapterPluginId: String,
) : DomainEvent

data class ProviderValidated(
    override val meta: EventMetadata,
    val providerId: ProviderId,
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
    val capabilities: List<CapabilityId>,
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
