package apap.testkit.inmemory

import apap.domain.model.conversation.Conversation
import apap.domain.model.conversation.Turn
import apap.domain.model.vo.ConversationId
import apap.domain.port.ConversationRepository

class NoSuchConversationException(
    id: ConversationId,
) : NoSuchElementException("Conversation not found: $id")

class InMemoryConversationRepository : ConversationRepository {
    private val conversations = mutableMapOf<ConversationId, Conversation>()

    override fun findById(id: ConversationId): Conversation? = conversations[id]

    override fun appendTurn(
        id: ConversationId,
        turn: Turn,
    ) {
        val existing = conversations[id] ?: throw NoSuchConversationException(id)
        conversations[id] = existing.appendTurn(turn)
    }

    override fun findTurns(
        id: ConversationId,
        seqRange: IntRange,
    ): List<Turn> {
        val conversation = conversations[id] ?: throw NoSuchConversationException(id)
        return conversation.turns.filter { it.seq in seqRange }
    }

    /** 04_ドメイン設計.md 4.3.4 / 02_システム仕様.md 2.16: 論理削除。 */
    override fun delete(id: ConversationId) {
        val existing = conversations[id] ?: throw NoSuchConversationException(id)
        conversations[id] = existing.delete()
    }

    fun save(conversation: Conversation) {
        conversations[conversation.conversationId] = conversation
    }
}
