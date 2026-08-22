package apap.context

import apap.domain.event.ConversationDeleted
import apap.domain.event.EventMetadata
import apap.domain.model.conversation.Conversation
import apap.domain.model.conversation.Turn
import apap.domain.model.conversation.TurnRole
import apap.domain.model.conversation.TurnSequenceViolationException
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.Usage
import apap.domain.port.Clock
import apap.domain.port.ConversationRepository
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator

class ConversationNotFoundException(
    id: ConversationId,
) : NoSuchElementException("Conversation not found: $id")

class ConversationAppendRetriesExhaustedException(
    id: ConversationId,
    attempts: Int,
) : RuntimeException("Failed to append a Turn to Conversation $id after $attempts concurrent-retry attempts")

/**
 * 02_システム仕様.md 2.16 / 04_ドメイン設計.md 4.3.4 Conversation Aggregate: 永続化・復元・削除の
 * オーケストレーション。seqの欠番なし単調増加は[Conversation.appendTurn]自身が強制し、
 * [ConversationRepository]（testkit実装は`ConcurrentHashMap.compute`で原子的）がその検証を
 * 並行呼出間でも有効にする。本クラスは「まず読んで期待seqを計算→書込→競合していたら再読込して
 * リトライ」という楽観的リトライを[maxSeqRetries]回まで行う。
 */
class ConversationManager(
    private val repository: ConversationRepository,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val eventPublisher: DomainEventPublisher,
    private val maxSeqRetries: Int = DEFAULT_MAX_SEQ_RETRIES,
) {
    fun start(
        sessionId: SessionId,
        tenantId: TenantId,
        title: String? = null,
    ): Conversation {
        val conversation =
            Conversation(
                conversationId = ConversationId(idGenerator.newId()),
                sessionId = sessionId,
                tenantId = tenantId,
                title = title,
            )
        repository.save(conversation)
        return conversation
    }

    @Suppress("ThrowsCount")
    fun appendTurn(
        conversationId: ConversationId,
        role: TurnRole,
        contentParts: List<ContentPart>,
        modelUsed: ModelId? = null,
        usage: Usage? = null,
    ): Turn {
        var lastError: TurnSequenceViolationException? = null
        repeat(maxSeqRetries) {
            val current = findOrThrow(conversationId)
            val turn =
                Turn(
                    turnId = idGenerator.newId(),
                    seq = current.turnCount + 1,
                    role = role,
                    contentParts = contentParts,
                    modelUsed = modelUsed,
                    usage = usage,
                    createdAt = clock.now(),
                )
            try {
                repository.appendTurn(conversationId, turn)
                return turn
            } catch (e: TurnSequenceViolationException) {
                lastError = e
            }
        }
        throw ConversationAppendRetriesExhaustedException(conversationId, maxSeqRetries)
            .apply { lastError?.let(::addSuppressed) }
    }

    fun history(
        conversationId: ConversationId,
        seqRange: IntRange = 1..Int.MAX_VALUE,
    ): List<Turn> = repository.findTurns(conversationId, seqRange)

    fun delete(
        conversationId: ConversationId,
        traceId: String,
    ) {
        val conversation = findOrThrow(conversationId)
        repository.delete(conversationId)
        eventPublisher.publish(
            ConversationDeleted(
                EventMetadata(
                    eventId = idGenerator.newId(),
                    occurredAt = clock.now(),
                    traceId = traceId,
                    tenantId = conversation.tenantId,
                    aggregateId = conversationId.value,
                    version = 0,
                ),
                conversationId,
            ),
        )
    }

    private fun findOrThrow(conversationId: ConversationId): Conversation =
        repository.findById(conversationId) ?: throw ConversationNotFoundException(conversationId)

    private companion object {
        const val DEFAULT_MAX_SEQ_RETRIES = 8
    }
}
