package apap.infrastructure.jdbc

import apap.domain.event.BatchItemCompleted
import apap.domain.event.BatchJobCompleted
import apap.domain.event.BatchJobStarted
import apap.domain.event.BatchJobSubmitted
import apap.domain.event.EventMetadata
import apap.domain.model.execution.BatchItem
import apap.domain.model.execution.BatchJobStatus
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.TenantId
import apap.testkit.inmemory.InMemoryClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.sql.DataSource

/** ローカルPostgreSQL（`docker compose -f tools/docker-compose.yaml up -d rdbms`）に対する統合テスト。 */
class JdbcBatchJobRepositoryTest {
    private lateinit var dataSource: DataSource
    private val jobId = "01ARZ3NDEKTSV4RRFFQ69G5FA7"
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")

    @BeforeEach
    fun setUp() {
        dataSource = JdbcTestSupport.freshDataSource()
    }

    private fun repo() =
        JdbcBatchJobRepository(
            dataSource,
            JdbcEventStoreRepository(dataSource, InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))),
            InMemoryClock(Instant.parse("2026-01-01T00:00:00Z")),
        )

    private fun meta(version: Long) =
        EventMetadata("evt-$version", Instant.parse("2026-01-01T00:00:00Z"), "trace-1", tenantId, jobId, version)

    @Test
    fun `findById reconstructs the current state from the appended events`() {
        val repo = repo()
        val items = listOf(BatchItem("item-1", 1), BatchItem("item-2", 2))
        repo.saveEvents(jobId, listOf(BatchJobSubmitted(meta(1), jobId, tenantId, CapabilityId("chat"), items)))
        repo.saveEvents(jobId, listOf(BatchJobStarted(meta(2), jobId)))
        repo.saveEvents(jobId, listOf(BatchItemCompleted(meta(3), jobId, "item-1", "COMPLETED")))
        repo.saveEvents(jobId, listOf(BatchItemCompleted(meta(4), jobId, "item-2", "COMPLETED")))
        repo.saveEvents(jobId, listOf(BatchJobCompleted(meta(5), jobId, 2, 2)))

        val found = repo.findById(jobId, tenantId)

        assertEquals(BatchJobStatus.COMPLETED, found?.status)
        assertEquals(2, found?.progress)
    }

    @Test
    fun `save writes the batch_job and batch_item read model rows without failing on the request_payload constraint`() {
        val repo = repo()
        val items = listOf(BatchItem("item-1", 1))
        val submitted = BatchJobSubmitted(meta(1), jobId, tenantId, CapabilityId("chat"), items)
        repo.saveEvents(jobId, listOf(submitted))

        repo.save(requireNotNull(repo.findById(jobId, tenantId)))

        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT status FROM batch_job WHERE job_id = ?").use { stmt ->
                stmt.setString(1, jobId)
                stmt.executeQuery().use { rs ->
                    assertEquals(true, rs.next())
                    assertEquals("SUBMITTED", rs.getString("status"))
                }
            }
        }
    }

    /**
     * P8後始末レビュー item3: 他テナントの`jobId`が供給された場合、存在しない場合と区別せず
     * nullを返す（[apap.domain.port.BatchJobRepository]のKDoc参照）。
     */
    @Test
    fun `findById returns null for a job that belongs to a different tenant`() {
        val repo = repo()
        val otherTenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA9")
        val items = listOf(BatchItem("item-1", 1))
        repo.saveEvents(jobId, listOf(BatchJobSubmitted(meta(1), jobId, tenantId, CapabilityId("chat"), items)))

        assertEquals(null, repo.findById(jobId, otherTenantId))
        assertEquals(tenantId, repo.findById(jobId, tenantId)?.tenantId)
    }
}
