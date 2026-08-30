package apap.infrastructure.persistence.inmemory

import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InMemoryQuotaSnapshotRepositoryTest {
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA2")

    @Test
    fun `defaults to unlimited, and setRemaining overrides the value for that key only`() {
        val repo = InMemoryQuotaSnapshotRepository()
        assertEquals(Int.MAX_VALUE, repo.remaining(tenantId, providerId, modelId))

        repo.setRemaining(tenantId, providerId, modelId, 5)

        assertEquals(5, repo.remaining(tenantId, providerId, modelId))
        assertEquals(Int.MAX_VALUE, repo.remaining(tenantId, providerId, ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA3")))
    }
}
