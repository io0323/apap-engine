package apap.domain.model.execution

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BatchJobTest {
    private fun job(
        status: BatchJobStatus = BatchJobStatus.SUBMITTED,
        items: List<BatchItem> = listOf(BatchItem("i1", 1)),
    ) = BatchJob(
        jobId = "job-1",
        tenantId = TenantId(testUlid('A')),
        targetCapability = CapabilityId("chat"),
        items = items,
        status = status,
    )

    @Test
    fun `requires at least one item`() {
        assertThrows(IllegalArgumentException::class.java) { job(items = emptyList()) }
    }

    @Test
    fun `BatchItem rejects seq below 1`() {
        assertThrows(IllegalArgumentException::class.java) { BatchItem("i1", 0) }
    }

    @Test
    fun `progress counts COMPLETED items`() {
        val withProgress = job(items = listOf(BatchItem("i1", 1, BatchItemStatus.COMPLETED), BatchItem("i2", 2)))
        assertEquals(1, withProgress.progress)
    }

    @Test
    fun `follows 9_5 lifecycle for the happy path`() {
        val queued = job().transitionTo(BatchJobStatus.QUEUED)
        val running = queued.transitionTo(BatchJobStatus.RUNNING)
        val completed = running.transitionTo(BatchJobStatus.COMPLETED)
        assertEquals(BatchJobStatus.COMPLETED, completed.status)
    }

    @Test
    fun `rejects any transition once COMPLETED`() {
        val completed =
            job()
                .transitionTo(BatchJobStatus.QUEUED)
                .transitionTo(BatchJobStatus.RUNNING)
                .transitionTo(BatchJobStatus.COMPLETED)
        assertThrows(BatchJobResultImmutableException::class.java) {
            completed.transitionTo(BatchJobStatus.FAILED)
        }
    }

    @Test
    fun `rejects illegal transitions`() {
        assertThrows(IllegalBatchJobStateTransitionException::class.java) {
            job().transitionTo(BatchJobStatus.RUNNING)
        }
    }

    @Test
    fun `allows cancellation from QUEUED and RUNNING`() {
        val queued = job().transitionTo(BatchJobStatus.QUEUED)
        assertEquals(BatchJobStatus.CANCELLED, queued.transitionTo(BatchJobStatus.CANCELLED).status)
        val running = queued.transitionTo(BatchJobStatus.RUNNING)
        assertEquals(BatchJobStatus.CANCELLED, running.transitionTo(BatchJobStatus.CANCELLED).status)
    }
}
