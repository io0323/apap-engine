package apap.domain.port

import apap.domain.model.conversation.Conversation
import apap.domain.model.conversation.Turn
import apap.domain.model.vo.ConversationId

/** 04_ドメイン設計.md 4.5: RepositoryはAggregate単位。Turn単独のRepositoryは持たない。 */
interface ConversationRepository {
    fun findById(id: ConversationId): Conversation?

    /**
     * 新規Conversationの永続化（既存の[appendTurn]は既存Conversationを前提とし、
     * 新規作成の経路を持たないための追加）。既存Conversationの状態変化（archive/delete等）の
     * 永続化にも使う。
     */
    fun save(conversation: Conversation)

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
