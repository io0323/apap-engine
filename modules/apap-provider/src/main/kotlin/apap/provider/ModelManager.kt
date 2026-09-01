package apap.provider

import apap.adapter.spi.DiscoveredModel
import apap.domain.event.AliasChanged
import apap.domain.event.AliasTargetSnapshot
import apap.domain.event.DomainEvent
import apap.domain.event.EventMetadata
import apap.domain.event.ModelDiscovered
import apap.domain.event.ModelRegistered
import apap.domain.event.ModelStatusChanged
import apap.domain.model.modelcatalog.AliasTarget
import apap.domain.model.modelcatalog.Model
import apap.domain.model.modelcatalog.ModelAlias
import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.vo.AliasId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.TenantId
import apap.domain.port.AliasRepository
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import apap.domain.port.ModelRepository

/** 02_システム仕様.md 2.7 Model登録情報。modelId/statusはModelManagerが決める。 */
data class RegisterModelCommand(
    val providerId: ProviderId,
    val modelName: String,
    val version: String,
    val capabilities: List<ModelCapability> = emptyList(),
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val regions: Set<Region>,
    val priority: Int,
)

/**
 * 02_システム仕様.md 2.3 Model Manager: Model登録/Status管理/Alias(Canary weight)/Discovery結果の取込。
 * 判断ロジック（状態遷移・Canary抽選）は[Model]/[ModelAlias]/`CanaryResolutionService`に委譲し、
 * 本クラスはリポジトリ永続化とイベント発行の手順を組み立てるだけに徹する。
 */
class ModelManager(
    private val modelRepository: ModelRepository,
    private val aliasRepository: AliasRepository,
    private val eventPublisher: DomainEventPublisher,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
) {
    fun register(command: RegisterModelCommand): Model {
        val model =
            Model(
                modelId = ModelId(idGenerator.newId()),
                providerId = command.providerId,
                modelName = command.modelName,
                version = command.version,
                capabilities = command.capabilities,
                contextWindow = command.contextWindow,
                maxOutputTokens = command.maxOutputTokens,
                regions = command.regions,
                priority = command.priority,
            )
        modelRepository.save(model)
        publishModelEvent(
            model.modelId,
            ModelRegistered(
                meta(model.modelId.value),
                model.modelId,
                model.providerId,
                model.capabilities,
                model.modelName,
                model.version,
                model.contextWindow,
                model.maxOutputTokens,
                model.regions,
                model.priority,
            ),
        )
        return model
    }

    /**
     * `target == RETIRED`の場合は[aliasRepository]から現在の有効な参照数を数えて[Model.retire]を、
     * それ以外は[Model.transitionTo]を呼ぶ（4.3.2不変条件: RETIREDはAlias参照ゼロが条件）。
     */
    fun changeStatus(
        modelId: ModelId,
        target: ModelStatus,
    ): Model {
        val model = requireModel(modelId)
        val from = model.status
        val updated =
            if (target == ModelStatus.RETIRED) {
                val activeAliasReferenceCount =
                    aliasRepository.listByModel(modelId).sumOf { alias ->
                        alias.targets.count { it.modelId == modelId }
                    }
                model.retire(activeAliasReferenceCount)
            } else {
                model.transitionTo(target)
            }
        modelRepository.save(updated)
        publishModelEvent(modelId, ModelStatusChanged(meta(modelId.value), modelId, from, target))
        return updated
    }

    /**
     * Alias（存在すれば置換、無ければ新規作成）を確定する。5.8のCanary weight変更（初期10%→後で100%）も
     * 同じシーケンス図で同一の`assignAlias`呼出として描かれているため、このメソッド1本で表現する。
     */
    fun assignAlias(
        tenantId: TenantId,
        aliasId: AliasId,
        name: String,
        targets: List<AliasTarget>,
    ): ModelAlias {
        val previous = aliasRepository.findByName(tenantId, name)
        val alias =
            ModelAlias.createWithModelStatusCheck(
                aliasId = aliasId,
                name = name,
                targets = targets,
                modelStatus = { modelId -> requireModel(modelId).status },
            )
        aliasRepository.save(tenantId, alias)
        publishAliasEvent(
            aliasId,
            AliasChanged(
                meta(aliasId.value),
                aliasId.value,
                alias.name,
                previous?.targets.orEmpty().map { AliasTargetSnapshot(it.modelId, it.weight) },
                alias.targets.map { AliasTargetSnapshot(it.modelId, it.weight) },
            ),
        )
        return alias
    }

    /**
     * 既存Aliasのtarget modelId集合はそのまま、weightだけ差し替える薄いラッパ（3.5のメソッド一覧に
     * 忠実。ロジックは[assignAlias]へ委譲し重複させない）。
     */
    fun setCanaryWeight(
        tenantId: TenantId,
        name: String,
        weights: Map<ModelId, Int>,
    ): ModelAlias {
        val current =
            checkNotNull(aliasRepository.findByName(tenantId, name)) { "ModelAlias not found: $name" }
        val newTargets =
            current.targets.map { target ->
                AliasTarget(target.modelId, weights[target.modelId] ?: target.weight)
            }
        return assignAlias(tenantId, current.aliasId, name, newTargets)
    }

    /**
     * 02_システム仕様.md 2.7: Discoveryは自動登録をせず、TESTING状態の登録候補として通知するだけ。
     * 既存登録済Model（同一provider内のmodelName+version）を除いた未登録分のみ`ModelDiscovered`として
     * 発行し、その一覧を返す。[register]は一切呼ばない。
     */
    fun discoverModels(
        providerId: ProviderId,
        discovered: List<DiscoveredModel>,
    ): List<DiscoveredModel> {
        val existing =
            modelRepository.findByProvider(providerId).map { it.modelName to it.version }.toSet()
        val unregistered = discovered.filterNot { (it.modelName to it.version) in existing }
        if (unregistered.isNotEmpty()) {
            publish(ModelDiscovered(meta(providerId.value), providerId, unregistered.map { it.modelName }))
        }
        return unregistered
    }

    private fun requireModel(id: ModelId): Model = modelRepository.findById(id) ?: error("Model not found: $id")

    private fun meta(aggregateId: String): EventMetadata =
        EventMetadata(
            eventId = idGenerator.newId(),
            occurredAt = clock.now(),
            traceId = idGenerator.newId(),
            tenantId = null,
            aggregateId = aggregateId,
            version = 0,
        )

    /**
     * [ModelDiscovered]はまだ登録されていないModel候補についての通知であり、特定のModel/Alias
     * Aggregateのstream_idに紐付かないため、Event Bus発行のみ行う（EventStoreへは永続化しない）。
     */
    private fun publish(event: DomainEvent) {
        eventPublisher.publish(event)
    }

    /**
     * ADR-0026: ProviderManagerの`publish`と同じdual-writeパターン（state保存 + Event Store永続化）を
     * ModelにもEvent Sourcing再構築のために適用する。
     */
    private fun publishModelEvent(
        modelId: ModelId,
        event: DomainEvent,
    ) {
        modelRepository.saveEvents(modelId, listOf(event))
        eventPublisher.publish(event)
    }

    private fun publishAliasEvent(
        aliasId: AliasId,
        event: DomainEvent,
    ) {
        aliasRepository.saveEvents(aliasId, listOf(event))
        eventPublisher.publish(event)
    }
}
