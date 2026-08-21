package apap.domain.model.modelcatalog

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ModelTest {
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))

    private fun model(status: ModelStatus = ModelStatus.REGISTERED) =
        Model(
            modelId = ModelId(testUlid('A')),
            providerId = ProviderId(testUlid('B')),
            modelName = "test-model",
            version = "v1",
            capabilities = listOf(ModelCapability(CapabilityId("chat"), mapOf("maxImages" to "4"))),
            contextWindow = 8000,
            maxOutputTokens = 1000,
            regions = setOf(region),
            status = status,
            priority = 50,
        )

    @Test
    fun `rejects maxOutputTokens exceeding contextWindow`() {
        assertThrows(IllegalArgumentException::class.java) {
            model().copy(contextWindow = 100, maxOutputTokens = 200)
        }
    }

    @Test
    fun `rejects non positive contextWindow or maxOutputTokens`() {
        assertThrows(IllegalArgumentException::class.java) { model().copy(contextWindow = 0) }
        assertThrows(IllegalArgumentException::class.java) { model().copy(maxOutputTokens = 0) }
    }

    @Test
    fun `rejects blank modelName or version`() {
        assertThrows(IllegalArgumentException::class.java) { model().copy(modelName = " ") }
        assertThrows(IllegalArgumentException::class.java) { model().copy(version = " ") }
    }

    @Test
    fun `rejects priority outside 1 to 100`() {
        assertThrows(IllegalArgumentException::class.java) { model().copy(priority = 0) }
        assertThrows(IllegalArgumentException::class.java) { model().copy(priority = 101) }
    }

    @Test
    fun `capabilities carry the capabilityId and free-form constraints`() {
        val capability = model().capabilities.single()
        assertEquals(CapabilityId("chat"), capability.capabilityId)
        assertEquals("4", capability.constraints["maxImages"])
    }

    @Test
    fun `follows the 9_2 lifecycle for the happy path`() {
        val testing = model().transitionTo(ModelStatus.TESTING)
        val active = testing.transitionTo(ModelStatus.ACTIVE)
        val deprecated = active.transitionTo(ModelStatus.DEPRECATED)
        assertEquals(ModelStatus.ACTIVE, deprecated.transitionTo(ModelStatus.ACTIVE).status)
    }

    @Test
    fun `allows TESTING back to REGISTERED as a rejection`() {
        val rejected = model().transitionTo(ModelStatus.TESTING).transitionTo(ModelStatus.REGISTERED)
        assertEquals(ModelStatus.REGISTERED, rejected.status)
    }

    @Test
    fun `rejects illegal transitions`() {
        assertThrows(IllegalModelStateTransitionException::class.java) {
            model().transitionTo(ModelStatus.ACTIVE)
        }
    }

    @Test
    fun `rejects direct transitionTo RETIRED, requiring retire()`() {
        val deprecated = deprecatedModel()
        assertThrows(IllegalArgumentException::class.java) { deprecated.transitionTo(ModelStatus.RETIRED) }
    }

    @Test
    fun `retire requires zero alias references`() {
        val deprecated = deprecatedModel()
        assertThrows(ModelStillReferencedByAliasException::class.java) {
            deprecated.retire(activeAliasReferenceCount = 2)
        }
        assertEquals(ModelStatus.RETIRED, deprecated.retire(activeAliasReferenceCount = 0).status)
    }

    private fun deprecatedModel() =
        model()
            .transitionTo(ModelStatus.TESTING)
            .transitionTo(ModelStatus.ACTIVE)
            .transitionTo(ModelStatus.DEPRECATED)
}
