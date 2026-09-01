package apap.domain.port

import apap.domain.event.DomainEvent
import apap.domain.model.execution.BatchJob
import apap.domain.model.vo.TenantId

/**
 * 04_ドメイン設計.md 4.5: BatchJobはEvent Sourcing対象（`saveEvents`で追記、ADR-0026）。
 * [ProviderRepository]と同じ形状（findById/save/saveEvents）に揃える。
 *
 * P8後始末レビュー item3: [BatchJob]は`tenantId`を保持する（12章ER図）。[findById]は
 * 他テナントの`jobId`が供給された場合、存在しない場合と区別せずnullを返すこと
 * （[apap.domain.port.ConversationRepository]と同じ方針）。
 */
interface BatchJobRepository {
    fun findById(
        jobId: String,
        tenantId: TenantId,
    ): BatchJob?

    fun save(job: BatchJob)

    fun saveEvents(
        jobId: String,
        events: List<DomainEvent>,
    )
}
