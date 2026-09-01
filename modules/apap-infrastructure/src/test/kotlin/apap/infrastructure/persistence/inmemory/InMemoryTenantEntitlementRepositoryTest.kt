package apap.infrastructure.persistence.inmemory

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TenantId
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InMemoryTenantEntitlementRepositoryTest {
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val capabilityId = CapabilityId("chat")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA1")

    @Test
    fun `defaults to permitted, and deny blocks exactly that combination`() {
        val repo = InMemoryTenantEntitlementRepository()
        assertTrue(repo.isPermitted(tenantId, capabilityId, modelId))

        repo.deny(tenantId, capabilityId, modelId)

        assertFalse(repo.isPermitted(tenantId, capabilityId, modelId))
        assertTrue(repo.isPermitted(tenantId, CapabilityId("embedding"), modelId))
    }
}
