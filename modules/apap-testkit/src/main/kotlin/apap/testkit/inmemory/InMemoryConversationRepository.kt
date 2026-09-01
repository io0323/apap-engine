package apap.testkit.inmemory

import apap.domain.model.conversation.Conversation
import apap.domain.model.conversation.Turn
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.TenantId
import apap.domain.port.ConversationRepository
import java.util.concurrent.ConcurrentHashMap

class NoSuchConversationException(
    id: ConversationId,
) : NoSuchElementException("Conversation not found: $id")

/**
 * [appendTurn]は`ConcurrentHashMap.compute`で読取→[Conversation.appendTurn]→書込を1つの
 * atomic operationとして行う（並行呼出時にseqの欠番/重複が起きないようにするため。
 * `mutableMapOf`の素朴な読取→書込では並行呼出間で競合しうる）。`Turn.seq`が既存の次期待値と
 * 食い違う場合は[apap.domain.model.conversation.TurnSequenceViolationException]がそのまま
 * 呼び出し側へ伝播する（呼び出し側が最新状態を読み直してリトライする設計、
 * `apap.context.ConversationManager`参照）。
 *
 * 他テナントの`ConversationId`が供給された場合は、存在しない場合と区別せず扱う
 * （[ConversationRepository]のKDoc参照。テナント間の存在有無の推測を防ぐため）。
 */
class InMemoryConversationRepository : ConversationRepository {
    private val conversations = ConcurrentHashMap<ConversationId, Conversation>()

    override fun findById(
        id: ConversationId,
        tenantId: TenantId,
    ): Conversation? = conversations[id]?.takeIf { it.tenantId == tenantId }

    override fun save(conversation: Conversation) {
        conversations[conversation.conversationId] = conversation
    }

    override fun appendTurn(
        id: ConversationId,
        tenantId: TenantId,
        turn: Turn,
    ) {
        conversations.compute(id) { _, existing ->
            (existing?.takeIf { it.tenantId == tenantId } ?: throw NoSuchConversationException(id)).appendTurn(turn)
        }
    }

    override fun findTurns(
        id: ConversationId,
        tenantId: TenantId,
        seqRange: IntRange,
    ): List<Turn> {
        val conversation =
            conversations[id]?.takeIf { it.tenantId == tenantId } ?: throw NoSuchConversationException(id)
        return conversation.turns.filter { it.seq in seqRange }
    }

    /** 04_ドメイン設計.md 4.3.4 / 02_システム仕様.md 2.16: 論理削除。 */
    override fun delete(
        id: ConversationId,
        tenantId: TenantId,
    ) {
        conversations.compute(id) { _, existing ->
            (existing?.takeIf { it.tenantId == tenantId } ?: throw NoSuchConversationException(id)).delete()
        }
    }
}
