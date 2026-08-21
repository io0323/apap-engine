package apap.domain.event

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.SessionId

/** 14_イベント一覧.md 14.5 Batch / Session系。 */
data class BatchJobSubmitted(
    override val meta: EventMetadata,
    val jobId: String,
    val targetCapability: CapabilityId,
) : DomainEvent

data class BatchJobStarted(
    override val meta: EventMetadata,
    val jobId: String,
) : DomainEvent

data class BatchItemCompleted(
    override val meta: EventMetadata,
    val jobId: String,
    val itemId: String,
    val status: String,
) : DomainEvent

data class BatchJobCompleted(
    override val meta: EventMetadata,
    val jobId: String,
    val completedItems: Int,
    val totalItems: Int,
) : DomainEvent

data class BatchJobFailed(
    override val meta: EventMetadata,
    val jobId: String,
    val reason: String,
) : DomainEvent

data class BatchJobCancelled(
    override val meta: EventMetadata,
    val jobId: String,
) : DomainEvent

data class SessionCreated(
    override val meta: EventMetadata,
    val sessionId: SessionId,
    val principal: String,
) : DomainEvent

data class SessionExpired(
    override val meta: EventMetadata,
    val sessionId: SessionId,
) : DomainEvent

data class SessionRevoked(
    override val meta: EventMetadata,
    val sessionId: SessionId,
    val reason: String,
) : DomainEvent

data class ConversationDeleted(
    override val meta: EventMetadata,
    val conversationId: ConversationId,
) : DomainEvent

data class MemoryStored(
    override val meta: EventMetadata,
    val memoryId: String,
    val scope: String,
) : DomainEvent

data class MemoryDeleted(
    override val meta: EventMetadata,
    val memoryId: String,
) : DomainEvent
