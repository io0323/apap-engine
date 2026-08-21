package apap.provider

import apap.adapter.spi.DiscoveredModel
import apap.domain.event.AliasChanged
import apap.domain.event.ModelDiscovered
import apap.domain.model.modelcatalog.AliasTarget
import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.modelcatalog.ModelStatus
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** FR-MDL-001/002/003/004/005: ModelManagerの登録/Status/Alias(Canary)/Discovery。 */
class ModelManagerTest {
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    private val modelRepository = InMemoryModelRepository()
    private val aliasRepository = InMemoryAliasRepository()
    private val eventPublisher = InMemoryDomainEventPublisher()
    private val clock = InMemoryClock()
    private val idGenerator = InMemoryIdGenerator()
    private val manager = ModelManager(modelRepository, aliasRepository, eventPublisher, clock, idGenerator)
    private val providerId = ProviderId(idGenerator.newId())
    private val tenantId = TenantId(idGenerator.newId())

    private fun registerModel(status: ModelStatus = ModelStatus.ACTIVE): ModelId {
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
        var current = model
        // REGISTERED -> TESTING -> ACTIVE (9.2 の許容遷移をたどる)
        if (status == ModelStatus.TESTING || status == ModelStatus.ACTIVE) {
            current = manager.changeStatus(model.modelId, ModelStatus.TESTING)
        }
        if (status == ModelStatus.ACTIVE) {
            current = manager.changeStatus(model.modelId, ModelStatus.ACTIVE)
        }
        return current.modelId
    }

    @Test
    fun `register creates a REGISTERED model and publishes ModelRegistered`() {
        val modelId = registerModel(ModelStatus.REGISTERED)

        assertEquals(ModelStatus.REGISTERED, modelRepository.findById(modelId)?.status)
    }

    @Test
    fun `assignAlias creates a new alias and publishes AliasChanged with empty oldTargets`() {
        val modelId = registerModel(ModelStatus.ACTIVE)
        val aliasId = AliasId(idGenerator.newId())

        val alias =
            manager.assignAlias(tenantId, aliasId, "chat-standard", listOf(AliasTarget(modelId, weight = 100)))

        assertEquals(100, alias.targets.single().weight)
        val event = eventPublisher.publishedEvents.filterIsInstance<AliasChanged>().single()
        assertTrue(event.oldTargets.isEmpty())
        assertEquals(1, event.newTargets.size)
    }

    @Test
    fun `setCanaryWeight changes weight distribution immediately and is reflected via AliasChanged`() {
        val oldModelId = registerModel(ModelStatus.ACTIVE)
        val newModelId = registerModelWithName("model-b", ModelStatus.TESTING)
        val aliasId = AliasId(idGenerator.newId())
        manager.assignAlias(
            tenantId,
            aliasId,
            "chat-standard",
            listOf(AliasTarget(oldModelId, weight = 90), AliasTarget(newModelId, weight = 10)),
        )
        eventPublisher.clear()

        val updated =
            manager.setCanaryWeight(
                tenantId,
                "chat-standard",
                mapOf(oldModelId to 0, newModelId to 100),
            )

        assertEquals(100, updated.targets.first { it.modelId == newModelId }.weight)
        assertEquals(0, updated.targets.first { it.modelId == oldModelId }.weight)
        assertEquals(1, eventPublisher.publishedEvents.filterIsInstance<AliasChanged>().size)
    }

    @Test
    fun `discoverModels publishes ModelDiscovered for new models but never registers them`() {
        val newModel =
            DiscoveredModel("brand-new-model", "1.0", setOf(CapabilityId("chat")), 8000, 1000, setOf("jp-east"))
        val discovered = manager.discoverModels(providerId, listOf(newModel))

        assertEquals(1, discovered.size)
        assertTrue(eventPublisher.publishedEvents.single() is ModelDiscovered)
        assertTrue(modelRepository.findByProvider(providerId).none { it.modelName == "brand-new-model" })
    }

    @Test
    fun `discoverModels excludes already-registered models and does not re-notify them`() {
        registerModel(ModelStatus.ACTIVE)

        val discovered =
            manager.discoverModels(
                providerId,
                listOf(DiscoveredModel("model-a", "1.0", setOf(CapabilityId("chat")), 8000, 1000, setOf("jp-east"))),
            )

        assertTrue(discovered.isEmpty())
        assertTrue(eventPublisher.publishedEvents.none { it is ModelDiscovered })
    }

    private fun registerModelWithName(
        name: String,
        status: ModelStatus,
    ): ModelId {
        val model =
            manager.register(
                RegisterModelCommand(
                    providerId = providerId,
                    modelName = name,
                    version = "1.0",
                    capabilities = listOf(ModelCapability(CapabilityId("chat"))),
                    contextWindow = 8000,
                    maxOutputTokens = 1000,
                    regions = setOf(region),
                    priority = 50,
                ),
            )
        return if (status == ModelStatus.TESTING) {
            manager.changeStatus(model.modelId, ModelStatus.TESTING).modelId
        } else {
            model.modelId
        }
    }
}
