package apap.domain.port

import apap.domain.model.conversation.Session
import apap.domain.model.vo.SessionId

/**
 * P8後始末レビュー item3で監査した結果、[ConversationRepository]/[BatchJobRepository]とは異なり、
 * ここへ`TenantId`引数を追加しない（意図的な判断）。[Session]自身は`tenantId`を保持するが、
 * `findById`（≒[apap.context.SessionManager.verify]の検証対象）は「このsessionIdは誰のものか」を
 * 判定するための入口そのものであり、呼び出し側がテナントを先に知っている前提を置けない
 * （[ConversationRepository]は逆に、既に認証済みのテナントコンテキストの中でconversationIdを
 * 解決するため、事前にtenantIdを渡して境界チェックできる）。
 *
 * 現時点で本Repositoryを呼ぶ実行経路は存在しない（`SessionManager`は未配線）。将来、Session管理API等
 * から呼び出す際は、`verify`が返した[Session.tenantId]を以後の処理の一次情報源として扱い、
 * 呼び出し側が別途「推定していたテナント」との突合が必要な場合（例: 管理APIでの越境操作防止）は、
 * 呼び出し側で`session.tenantId`を検証すること。要件充足に影響しない実装判断のためADR化せず
 * ここに根拠を記す。
 */
interface SessionRepository {
    fun findById(id: SessionId): Session?

    fun save(session: Session)

    fun expire(id: SessionId)
}
