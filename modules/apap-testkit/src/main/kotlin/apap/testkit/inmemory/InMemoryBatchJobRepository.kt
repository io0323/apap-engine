package apap.testkit.inmemory

import apap.domain.event.DomainEvent
import apap.domain.model.execution.BatchJob
import apap.domain.port.BatchJobRepository

class InMemoryBatchJobRepository : BatchJobRepository {
    private val jobs = mutableMapOf<String, BatchJob>()
    private val eventsById = mutableMapOf<String, MutableList<DomainEvent>>()

    override fun findById(jobId: String): BatchJob? = jobs[jobId]

    override fun save(job: BatchJob) {
        jobs[job.jobId] = job
    }

    override fun saveEvents(
        jobId: String,
        events: List<DomainEvent>,
    ) {
        eventsById.getOrPut(jobId) { mutableListOf() }.addAll(events)
    }

    fun eventsFor(jobId: String): List<DomainEvent> = eventsById[jobId].orEmpty()
}
