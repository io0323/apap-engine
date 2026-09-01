package apap.domain.port

import apap.domain.model.conversation.Conversation
import apap.domain.model.conversation.Turn
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.TenantId

/**
 * 04_ドメイン設計.md 4.5: RepositoryはAggregate単位。Turn単独のRepositoryは持たない。
 *
 * P8後始末レビュー item3: [Conversation]は`tenantId`を保持するが、以前は`findById`等が
 * `ConversationId`のみで解決しテナント境界を検証していなかった（他テナントの`conversationId`を
 * 供給されると横断的に読み書きできてしまう、実行経路上のバグ）。[MemoryRepository.searchByVector]の
 * 修正と同じ方針で、テナントを跨いだ`conversationId`の推測・列挙による情報漏えいを避けるため、
 * 該当Conversationが別テナントのものであれば「存在しない」のと同じ扱い（[findById]はnull、
 * その他は"not found"系例外）とする。実装は[Conversation.tenantId]との一致を確認すること。
 */
interface ConversationRepository {
    fun findById(
        id: ConversationId,
        tenantId: TenantId,
    ): Conversation?

    /**
     * 新規Conversationの永続化（既存の[appendTurn]は既存Conversationを前提とし、
     * 新規作成の経路を持たないための追加）。既存Conversationの状態変化（archive/delete等）の
     * 永続化にも使う。
     */
    fun save(conversation: Conversation)

    fun appendTurn(
        id: ConversationId,
        tenantId: TenantId,
        turn: Turn,
    )

    fun findTurns(
        id: ConversationId,
        tenantId: TenantId,
        seqRange: IntRange,
    ): List<Turn>

    fun delete(
        id: ConversationId,
        tenantId: TenantId,
    )
}
