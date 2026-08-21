package apap.domain.model.execution

import apap.domain.model.IllegalStateTransitionException
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.TenantId

/** 09_状態遷移図.md 9.5。 */
enum class BatchJobStatus { SUBMITTED, QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

enum class BatchItemStatus { PENDING, RUNNING, COMPLETED, FAILED }

class IllegalBatchJobStateTransitionException(
    from: BatchJobStatus,
    to: BatchJobStatus,
) : IllegalStateTransitionException("Illegal BatchJob transition: $from -> $to")

class BatchJobResultImmutableException(
    jobId: String,
) : IllegalStateTransitionException("BatchJob $jobId is COMPLETED; its result is immutable")

data class BatchItem(
    val itemId: String,
    val seq: Int,
    val status: BatchItemStatus = BatchItemStatus.PENDING,
) {
    init {
        require(seq >= 1) { "seq must start at 1: $seq" }
    }
}

/**
 * 04_ドメイン設計.md 4.3.5 BatchJob Aggregate（Root）。
 * 状態遷移は09_状態遷移図.md 9.5のみ。COMPLETEDの結果は不変（[transitionTo]は
 * COMPLETED到達後の遷移を常に拒否する）。
 */
data class BatchJob(
    val jobId: String,
    val tenantId: TenantId,
    val targetCapability: CapabilityId,
    val items: List<BatchItem>,
    val status: BatchJobStatus = BatchJobStatus.SUBMITTED,
) {
    init {
        require(items.isNotEmpty()) { "BatchJob must have at least one item" }
    }

    val progress: Int get() = items.count { it.status == BatchItemStatus.COMPLETED }

    fun transitionTo(target: BatchJobStatus): BatchJob {
        if (status == BatchJobStatus.COMPLETED) {
            throw BatchJobResultImmutableException(jobId)
        }
        val allowed = ALLOWED_TRANSITIONS[status].orEmpty()
        if (target !in allowed) {
            throw IllegalBatchJobStateTransitionException(status, target)
        }
        return copy(status = target)
    }

    companion object {
        // 9.5: SUBMITTED->QUEUED|FAILED、QUEUED->RUNNING|CANCELLED、
        //      RUNNING->COMPLETED|FAILED|CANCELLED
        private val ALLOWED_TRANSITIONS: Map<BatchJobStatus, Set<BatchJobStatus>> =
            mapOf(
                BatchJobStatus.SUBMITTED to setOf(BatchJobStatus.QUEUED, BatchJobStatus.FAILED),
                BatchJobStatus.QUEUED to setOf(BatchJobStatus.RUNNING, BatchJobStatus.CANCELLED),
                BatchJobStatus.RUNNING to
                    setOf(BatchJobStatus.COMPLETED, BatchJobStatus.FAILED, BatchJobStatus.CANCELLED),
                BatchJobStatus.COMPLETED to emptySet(),
                BatchJobStatus.FAILED to emptySet(),
                BatchJobStatus.CANCELLED to emptySet(),
            )
    }
}
