package apap.context

import apap.domain.event.EventMetadata
import apap.domain.event.MemoryDeleted
import apap.domain.event.MemoryStored
import apap.domain.model.conversation.Memory
import apap.domain.model.conversation.MemoryScope
import apap.domain.model.vo.TenantId
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import apap.domain.port.MemoryRepository
import java.time.Instant

/** 02_システム仕様.md 2.17 / 04_ドメイン設計.md 4.3.5 Memory Aggregate: 保存/検索/削除のオーケストレーション。 */
@Suppress("LongParameterList")
class MemoryManager(
    private val repository: MemoryRepository,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val eventPublisher: DomainEventPublisher,
) {
    fun store(
        tenantId: TenantId,
        scope: MemoryScope,
        content: String,
        embedding: List<Double>,
        importance: Double,
        traceId: String,
        ttlAt: Instant? = null,
    ): Memory {
        val memoryId = idGenerator.newId()
        val memory =
            Memory(
                memoryId = memoryId,
                tenantId = tenantId,
                scope = scope,
                content = content,
                embedding = embedding,
                importance = importance,
                ttlAt = ttlAt,
                lastAccessedAt = clock.now(),
            )
        repository.store(memory)
        eventPublisher.publish(MemoryStored(meta(memoryId, tenantId, traceId), memoryId, scope.name))
        return memory
    }

    fun delete(
        memoryId: String,
        tenantId: TenantId,
        traceId: String,
    ) {
        repository.delete(memoryId)
        eventPublisher.publish(MemoryDeleted(meta(memoryId, tenantId, traceId), memoryId))
    }

    /** 02_システム仕様.md 2.17: top-k類似検索（既定top-k/類似度閾値は呼び出し側が指定する）。 */
    fun search(
        tenantId: TenantId,
        scopes: Set<MemoryScope>,
        queryVector: List<Double>,
        topK: Int,
        threshold: Double,
    ): List<Memory> = repository.searchByVector(tenantId, scopes, queryVector, topK, threshold)

    private fun meta(
        memoryId: String,
        tenantId: TenantId,
        traceId: String,
    ): EventMetadata =
        EventMetadata(
            eventId = idGenerator.newId(),
            occurredAt = clock.now(),
            traceId = traceId,
            tenantId = tenantId,
            aggregateId = memoryId,
            version = 0,
        )
}
