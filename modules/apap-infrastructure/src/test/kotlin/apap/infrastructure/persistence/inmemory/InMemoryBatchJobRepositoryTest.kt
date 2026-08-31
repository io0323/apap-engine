package apap.infrastructure.persistence.inmemory

import apap.domain.model.execution.BatchItem
import apap.domain.model.execution.BatchJob
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InMemoryBatchJobRepositoryTest {
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val otherTenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA9")

    private fun job() =
        BatchJob(
            jobId = "01ARZ3NDEKTSV4RRFFQ69G5FA1",
            tenantId = tenantId,
            targetCapability = CapabilityId("chat"),
            items = listOf(BatchItem("item-1", 1)),
        )

    @Test
    fun `findById returns the job for its own tenant`() {
        val repo = InMemoryBatchJobRepository()
        repo.save(job())

        assertEquals(job(), repo.findById(job().jobId, tenantId))
    }

    /** P8後始末レビュー item3: 別テナントの`jobId`は存在しない場合と区別せずnullを返す。 */
    @Test
    fun `findById returns null for a job that belongs to a different tenant`() {
        val repo = InMemoryBatchJobRepository()
        repo.save(job())

        assertNull(repo.findById(job().jobId, otherTenantId))
    }
}
