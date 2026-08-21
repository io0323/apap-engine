package apap.execution

import java.util.concurrent.ConcurrentHashMap

/** [IdempotencyGuard.claim]が同一キーの並行実行を検出した際に送出する（13_API設計.md 13.4 `CONFLICT`）。 */
class DuplicateRequestException(
    val key: String,
) : Exception("Duplicate concurrent request for idempotency key: $key")

/**
 * 02_システム仕様.md 2.11「冪等性: 冪等キー（クライアント指定 or 自動生成）で二重実行防止」/
 * NFR-AVL-003。同一`(tenantId, idempotencyKey)`の**並行**実行を拒否する。
 *
 * 完了済みリクエストの結果再生（同一キーでの後続リクエストに過去の応答をそのまま返す）は
 * Cache Engine（Request Cache、2.14）の責務であり、本フェーズではPassthroughスタブのため対象外
 * （requirements-matrix.mdに部分実装として記載）。ここで防ぐのは「処理中の二重実行」のみ。
 */
class IdempotencyGuard {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** @throws DuplicateRequestException 同一キーが既に処理中の場合。[key]がnullなら何もしない。 */
    fun claim(key: String?) {
        if (key == null) return
        if (!inFlight.add(key)) throw DuplicateRequestException(key)
    }

    fun release(key: String?) {
        if (key == null) return
        inFlight.remove(key)
    }
}
