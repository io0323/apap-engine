package apap.domain.model.execution

import apap.domain.event.BatchItemCompleted
import apap.domain.event.BatchJobCompleted
import apap.domain.event.BatchJobStarted
import apap.domain.event.BatchJobSubmitted
import apap.domain.event.EventMetadata
import apap.domain.event.PolicyUpdated
import apap.domain.model.UnexpectedEventForAggregateException
import apap.domain.model.reconstruct
import apap.domain.model.routing.PolicyStatus
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

/** ADR-0026: イベント列からのBatchJob再構築が、コマンド適用後の状態と一致することを検証する。 */
class BatchJobReconstructionTest {
    private val jobId = testUlid('G')
    private val tenantId = TenantId(testUlid('A'))
    private val items = listOf(BatchItem("item-1", 1), BatchItem("item-2", 2))

    private fun meta(version: Long) =
        EventMetadata(
            eventId = "evt-$version",
            occurredAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(version),
            traceId = "trace-1",
            tenantId = tenantId,
            aggregateId = jobId,
            version = version,
        )

    private fun submitted() = BatchJobSubmitted(meta(1), jobId, tenantId, CapabilityId("chat"), items)

    @Test
    fun `reconstruction from the full event list matches the state a live command flow would reach`() {
        val started = BatchJobStarted(meta(2), jobId)
        val item1Done = BatchItemCompleted(meta(3), jobId, "item-1", "COMPLETED")
        val item2Done = BatchItemCompleted(meta(4), jobId, "item-2", "COMPLETED")
        val completed = BatchJobCompleted(meta(5), jobId, 2, 2)
        val events = listOf(submitted(), started, item1Done, item2Done, completed)

        val result = reconstruct(events, null, ::applyBatchJobEvent)

        assertEquals(BatchJobStatus.COMPLETED, result?.status)
        assertEquals(2, result?.progress)
        assertEquals(
            listOf(BatchItemStatus.COMPLETED, BatchItemStatus.COMPLETED),
            result?.items?.map { it.status },
        )
    }

    @Test
    fun `SUBMITTED to QUEUED has no corresponding event, so replay jumps straight to RUNNING`() {
        val started = BatchJobStarted(meta(2), jobId)

        val result = reconstruct(listOf(submitted(), started), null, ::applyBatchJobEvent)

        assertEquals(BatchJobStatus.RUNNING, result?.status)
    }

    @Test
    fun `reconstructing from a snapshot plus only the events since it matches full replay`() {
        val events =
            listOf(
                submitted(),
                BatchJobStarted(meta(2), jobId),
                BatchItemCompleted(meta(3), jobId, "item-1", "COMPLETED"),
            )
        val fullReplay = reconstruct(events, null, ::applyBatchJobEvent)

        val snapshotState = reconstruct(events.take(2), null, ::applyBatchJobEvent)
        val fromSnapshot = reconstruct(events.drop(2), snapshotState, ::applyBatchJobEvent)

        assertEquals(fullReplay, fromSnapshot)
    }

    @Test
    fun `an event that does not belong to BatchJob throws instead of silently ignoring it`() {
        val unrelated = PolicyUpdated(meta(9), "policy-1", "PLATFORM", null, null, emptyList(), 1, PolicyStatus.ACTIVE)
        assertThrows(UnexpectedEventForAggregateException::class.java) {
            applyBatchJobEvent(reconstruct(listOf(submitted()), null, ::applyBatchJobEvent), unrelated)
        }
    }
}
