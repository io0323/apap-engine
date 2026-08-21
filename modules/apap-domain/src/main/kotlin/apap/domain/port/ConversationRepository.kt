package apap.domain.port

import apap.domain.model.conversation.Conversation
import apap.domain.model.conversation.Turn
import apap.domain.model.vo.ConversationId

/** 04_ドメイン設計.md 4.5: RepositoryはAggregate単位。Turn単独のRepositoryは持たない。 */
interface ConversationRepository {
    fun findById(id: ConversationId): Conversation?

    fun appendTurn(
        id: ConversationId,
        turn: Turn,
    )

    fun findTurns(
        id: ConversationId,
        seqRange: IntRange,
    ): List<Turn>

    fun delete(id: ConversationId)
}
