package apap.infrastructure.jdbc

import apap.domain.event.DomainEvent
import apap.domain.model.execution.BatchItem
import apap.domain.model.execution.BatchJob
import apap.domain.model.execution.applyBatchJobEvent
import apap.domain.model.vo.TenantId
import apap.domain.port.BatchJobRepository
import apap.domain.port.Clock
import apap.domain.port.EventStoreRepository
import java.sql.Connection
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * [BatchJobRepository]のJDBC実装（ADR-0025、ADR-0026）。`findById`は[EventStoreRepository]経由の
 * 再構築を真の情報源とし、`batch_job`/`batch_item`テーブルはRead Model射影として`save`が
 * 同期的に書き込む（[JdbcProviderRepository]と同じ設計判断）。
 *
 * `batch_item.request_payload`はNOT NULL列だが、[BatchItem]（apap-domain）自身はリクエスト本文を
 * 保持しない（itemId/seq/statusのみ）ため、空オブジェクト`{}`を書き込む。本タスクの範囲では
 * BatchItemの実行内容そのものを要求するFR/NFRは対象外のため、要件充足に影響しない実装判断として
 * ADR化せずここに根拠を記す。
 *
 * P8後始末レビュー item3: [findById]は他テナントの`jobId`が供給された場合、存在しない場合と
 * 区別せずnullを返す（[BatchJobRepository]のKDoc参照）。
 */
class JdbcBatchJobRepository(
    private val dataSource: DataSource,
    private val eventStoreRepository: EventStoreRepository,
    private val clock: Clock,
    snapshotEveryNEvents: Int = 100,
) : BatchJobRepository {
    private val support =
        EventSourcedRepositorySupport(eventStoreRepository, BatchJob::class, ::applyBatchJobEvent, snapshotEveryNEvents)

    override fun findById(
        jobId: String,
        tenantId: TenantId,
    ): BatchJob? = support.reconstruct(jobId)?.takeIf { it.tenantId == tenantId }

    @Suppress("TooGenericExceptionCaught")
    override fun save(job: BatchJob) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                upsertJob(conn, job)
                replaceItems(conn, job)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    @Suppress("MagicNumber")
    private fun upsertJob(
        conn: Connection,
        job: BatchJob,
    ) {
        conn
            .prepareStatement(
                """
                INSERT INTO batch_job (
                    job_id, tenant_id, target_capability, status, total_items, completed_items, submitted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (job_id) DO UPDATE SET
                    status = EXCLUDED.status, total_items = EXCLUDED.total_items,
                    completed_items = EXCLUDED.completed_items
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, job.jobId)
                stmt.setString(2, job.tenantId.value)
                stmt.setString(3, job.targetCapability.value)
                stmt.setString(4, job.status.name)
                stmt.setInt(5, job.items.size)
                stmt.setInt(6, job.progress)
                stmt.setTimestamp(7, Timestamp.from(clock.now()))
                stmt.executeUpdate()
            }
    }

    private fun replaceItems(
        conn: Connection,
        job: BatchJob,
    ) {
        conn.prepareStatement("DELETE FROM batch_item WHERE job_id = ?").use { stmt ->
            stmt.setString(1, job.jobId)
            stmt.executeUpdate()
        }
        job.items.forEach { item -> insertItem(conn, job.jobId, item) }
    }

    @Suppress("MagicNumber")
    private fun insertItem(
        conn: Connection,
        jobId: String,
        item: BatchItem,
    ) {
        conn
            .prepareStatement(
                "INSERT INTO batch_item (item_id, job_id, seq, request_payload, status) " +
                    "VALUES (?, ?, ?, '{}'::jsonb, ?)",
            ).use { stmt ->
                stmt.setString(1, item.itemId)
                stmt.setString(2, jobId)
                stmt.setInt(3, item.seq)
                stmt.setString(4, item.status.name)
                stmt.executeUpdate()
            }
    }

    override fun saveEvents(
        jobId: String,
        events: List<DomainEvent>,
    ) {
        support.saveEvents(jobId, events)
    }
}
