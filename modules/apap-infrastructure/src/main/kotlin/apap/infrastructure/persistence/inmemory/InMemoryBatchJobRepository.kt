package apap.infrastructure.persistence.inmemory

import apap.domain.event.DomainEvent
import apap.domain.model.execution.BatchJob
import apap.domain.port.BatchJobRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** [BatchJobRepository]の本番用In-Memory実装。04_ドメイン設計.md 4.5によりEvent Sourcing対象（ADR-0026）。 */
class InMemoryBatchJobRepository : BatchJobRepository {
    private val jobs = ConcurrentHashMap<String, BatchJob>()
    private val eventsById = ConcurrentHashMap<String, CopyOnWriteArrayList<DomainEvent>>()

    override fun findById(jobId: String): BatchJob? = jobs[jobId]

    override fun save(job: BatchJob) {
        jobs[job.jobId] = job
    }

    override fun saveEvents(
        jobId: String,
        events: List<DomainEvent>,
    ) {
        eventsById.computeIfAbsent(jobId) { CopyOnWriteArrayList() }.addAll(events)
    }
}
