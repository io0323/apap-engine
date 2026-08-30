package apap.infrastructure.persistence.inmemory

import apap.domain.model.conversation.Conversation
import apap.domain.model.conversation.Turn
import apap.domain.model.vo.ConversationId
import apap.domain.port.ConversationRepository
import java.util.concurrent.ConcurrentHashMap

class NoSuchConversationException(
    id: ConversationId,
) : NoSuchElementException("Conversation not found: $id")

/**
 * [ConversationRepository]の本番用In-Memory実装（単一プロセス埋込利用の既定、ADR-0001）。
 *
 * [appendTurn]は`ConcurrentHashMap.compute`で読取→[Conversation.appendTurn]→書込を1つの
 * atomic operationとして行う（並行呼出時にseqの欠番/重複が起きないようにするため）。
 * `Turn.seq`が既存の次期待値と食い違う場合は[apap.domain.model.conversation.TurnSequenceViolationException]
 * がそのまま呼び出し側へ伝播する（呼び出し側が最新状態を読み直してリトライする設計、
 * `apap.context.ConversationManager`参照）。
 */
class InMemoryConversationRepository : ConversationRepository {
    private val conversations = ConcurrentHashMap<ConversationId, Conversation>()

    override fun findById(id: ConversationId): Conversation? = conversations[id]

    override fun save(conversation: Conversation) {
        conversations[conversation.conversationId] = conversation
    }

    override fun appendTurn(
        id: ConversationId,
        turn: Turn,
    ) {
        conversations.compute(id) { _, existing ->
            (existing ?: throw NoSuchConversationException(id)).appendTurn(turn)
        }
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
        conversations.compute(id) { _, existing ->
            (existing ?: throw NoSuchConversationException(id)).delete()
        }
    }
}
