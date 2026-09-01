package apap.provider

import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.modelcatalog.applyModelAliasEvent
import apap.domain.model.modelcatalog.applyModelEvent
import apap.domain.model.reconstruct
import apap.domain.model.vo.AliasId
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.TenantId
import apap.testkit.inmemory.InMemoryAliasRepository
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import apap.testkit.inmemory.InMemoryModelRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * ADR-0026 / P8後始末レビュー item2: [ModelManager]経由の状態変化が、発行されたイベントだけから
 * 再構築した状態と一致することを検証する（[ProviderManagerEventRoundTripTest]と同じ趣旨）。
 * 今回`ModelManager`が`eventPublisher.publish()`のみでEvent Storeへ一度も書いていなかった
 * （`modelRepository.saveEvents`/`aliasRepository.saveEvents`が呼ばれていなかった）バグを
 * 発見した経緯そのものが、このテストが埋める空白の実例。
 */
class ModelManagerEventRoundTripTest {
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    private val modelRepository = InMemoryModelRepository()
    private val aliasRepository = InMemoryAliasRepository()
    private val eventPublisher = InMemoryDomainEventPublisher()
    private val clock = InMemoryClock()
    private val idGenerator = InMemoryIdGenerator()
    private val manager = ModelManager(modelRepository, aliasRepository, eventPublisher, clock, idGenerator)
    private val providerId = ProviderId(idGenerator.newId())
    private val tenantId = TenantId(idGenerator.newId())

    private fun assertModelReconstructionMatchesLiveState(modelId: ModelId) {
        val live = modelRepository.findById(modelId)
        val reconstructed = reconstruct(modelRepository.eventsFor(modelId), null, ::applyModelEvent)
        assertEquals(live, reconstructed)
    }

    private fun assertAliasReconstructionMatchesLiveState(aliasId: AliasId) {
        val live = aliasRepository.findByName(tenantId, "chat-standard")
        val reconstructed = reconstruct(aliasRepository.eventsFor(aliasId), null, ::applyModelAliasEvent)
        assertEquals(live, reconstructed)
    }

    @Test
    fun `register, changeStatus through to RETIRED round-trips through events`() {
        val model =
            manager.register(
                RegisterModelCommand(
                    providerId = providerId,
                    modelName = "model-a",
                    version = "1.0",
                    capabilities = listOf(ModelCapability(CapabilityId("chat"))),
                    contextWindow = 8000,
                    maxOutputTokens = 1000,
                    regions = setOf(region),
                    priority = 50,
                ),
            )
        assertModelReconstructionMatchesLiveState(model.modelId)

        manager.changeStatus(model.modelId, ModelStatus.TESTING)
        assertModelReconstructionMatchesLiveState(model.modelId)

        manager.changeStatus(model.modelId, ModelStatus.ACTIVE)
        assertModelReconstructionMatchesLiveState(model.modelId)

        manager.changeStatus(model.modelId, ModelStatus.DEPRECATED)
        assertModelReconstructionMatchesLiveState(model.modelId)

        // 4.3.2不変条件によりAlias参照ゼロが条件。ここでは一度もaliasを割り当てていないので満たす。
        manager.changeStatus(model.modelId, ModelStatus.RETIRED)
        assertModelReconstructionMatchesLiveState(model.modelId)
        assertEquals(ModelStatus.RETIRED, modelRepository.findById(model.modelId)?.status)
    }

    @Test
    fun `assignAlias create then canary-shift round-trips through events for both Model and ModelAlias`() {
        val model =
            manager.register(
                RegisterModelCommand(
                    providerId = providerId,
                    modelName = "model-a",
                    version = "1.0",
                    capabilities = emptyList(),
                    contextWindow = 8000,
                    maxOutputTokens = 1000,
                    regions = setOf(region),
                    priority = 50,
                ),
            )
        manager.changeStatus(model.modelId, ModelStatus.TESTING)
        val secondModel =
            manager.register(
                RegisterModelCommand(
                    providerId = providerId,
                    modelName = "model-b",
                    version = "1.0",
                    capabilities = emptyList(),
                    contextWindow = 8000,
                    maxOutputTokens = 1000,
                    regions = setOf(region),
                    priority = 50,
                ),
            )
        manager.changeStatus(secondModel.modelId, ModelStatus.TESTING)

        val aliasId = AliasId(idGenerator.newId())
        manager.assignAlias(
            tenantId,
            aliasId,
            "chat-standard",
            listOf(
                apap.domain.model.modelcatalog
                    .AliasTarget(model.modelId, 100),
                apap.domain.model.modelcatalog
                    .AliasTarget(secondModel.modelId, 0),
            ),
        )
        assertAliasReconstructionMatchesLiveState(aliasId)

        manager.setCanaryWeight(tenantId, "chat-standard", mapOf(model.modelId to 90, secondModel.modelId to 10))
        assertAliasReconstructionMatchesLiveState(aliasId)
        assertEquals(
            90,
            aliasRepository
                .findByName(tenantId, "chat-standard")
                ?.targets
                ?.first { it.modelId == model.modelId }
                ?.weight,
        )
    }
}
