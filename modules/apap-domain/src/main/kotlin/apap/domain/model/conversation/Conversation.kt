package apap.domain.model.conversation

import apap.domain.model.IllegalStateTransitionException
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.Usage
import java.time.Instant

enum class ConversationStatus { ACTIVE, ARCHIVED, DELETED }

enum class TurnRole { SYSTEM, USER, ASSISTANT, TOOL }

class TurnSequenceViolationException(
    expectedSeq: Int,
    actualSeq: Int,
) : IllegalStateTransitionException(
        "Turn.seq must be monotonically increasing without gaps: expected=$expectedSeq, actual=$actualSeq",
    )

class ConversationDeletedException(
    conversationId: ConversationId,
) : IllegalStateTransitionException("Cannot append a Turn to a DELETED Conversation: $conversationId")

/** 04_ドメイン設計.md 4.3.4 Turn（Entity）: roleはsystem/user/assistant/toolのみ。 */
data class Turn(
    val turnId: String,
    val seq: Int,
    val role: TurnRole,
    val contentParts: List<ContentPart>,
    val modelUsed: ModelId? = null,
    val usage: Usage? = null,
    val createdAt: Instant,
) {
    init {
        require(seq >= 1) { "Turn.seq must start at 1: $seq" }
        require(contentParts.isNotEmpty()) { "Turn must have at least one ContentPart" }
    }
}

/**
 * 04_ドメイン設計.md 4.3.4 Conversation Aggregate（Root）。
 * 不変条件: Turn seqは欠番なし単調増加。DELETED後の追記不可。
 */
data class Conversation(
    val conversationId: ConversationId,
    val sessionId: SessionId,
    val tenantId: TenantId,
    val title: String? = null,
    val status: ConversationStatus = ConversationStatus.ACTIVE,
    val turns: List<Turn> = emptyList(),
) {
    val turnCount: Int get() = turns.size

    fun appendTurn(turn: Turn): Conversation {
        if (status == ConversationStatus.DELETED) {
            throw ConversationDeletedException(conversationId)
        }
        val expectedSeq = turns.size + 1
        if (turn.seq != expectedSeq) {
            throw TurnSequenceViolationException(expectedSeq, turn.seq)
        }
        return copy(turns = turns + turn)
    }

    fun archive(): Conversation = copy(status = ConversationStatus.ARCHIVED)

    fun delete(): Conversation = copy(status = ConversationStatus.DELETED)
}
