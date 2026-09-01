package apap.infrastructure.persistence.inmemory

import apap.domain.model.modelcatalog.Model
import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InMemoryModelRepositoryTest {
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1")

    private fun model(id: String) =
        Model(
            modelId = ModelId(id),
            providerId = providerId,
            modelName = "test-model",
            version = "v1",
            capabilities = listOf(ModelCapability(CapabilityId("chat"))),
            contextWindow = 8000,
            maxOutputTokens = 1000,
            regions = setOf(region),
            priority = 50,
        )

    @Test
    fun `findByProvider and findByCapability filter correctly`() {
        val repo = InMemoryModelRepository()
        repo.save(model("01ARZ3NDEKTSV4RRFFQ69G5FA0"))

        assertEquals(1, repo.findByProvider(providerId).size)
        assertEquals(1, repo.findByCapability(CapabilityId("chat")).size)
        assertEquals(0, repo.findByCapability(CapabilityId("embedding")).size)
    }
}
