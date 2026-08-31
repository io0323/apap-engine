package apap.domain.port

import apap.domain.event.DomainEvent
import apap.domain.model.execution.BatchJob

/**
 * 04_ドメイン設計.md 4.5: BatchJobはEvent Sourcing対象（`saveEvents`で追記、ADR-0026）。
 * [ProviderRepository]と同じ形状（findById/save/saveEvents）に揃える。
 */
interface BatchJobRepository {
    fun findById(jobId: String): BatchJob?

    fun save(job: BatchJob)

    fun saveEvents(
        jobId: String,
        events: List<DomainEvent>,
    )
}
