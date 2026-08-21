package apap.domain.model.execution

import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import java.time.Duration
import java.time.Instant

/**
 * 03_基本設計.md 3.15: 「リクエスト状態は `ExecutionContext` オブジェクトとして引数伝播」
 * （01_CLAUDE.md 不変条件5: ThreadLocal/CoroutineContext要素で実行状態を運ばない。
 * ストリーム・非同期でも安全であること）。
 *
 * タイムアウト予算は開始時刻からの絶対締切（[deadline]）として保持する。可変フィールドを持たない
 * immutableな値のまま各層（ExecutionEngine → FallbackEngine → AttemptExecutor → ...）へ明示的に
 * 渡し続けられるようにするため、残余時間は都度[remaining]で計算する（残量を保持するミュータブルな
 * フィールドは持たない）。
 */
data class ExecutionContext(
    val requestId: RequestId,
    val tenantId: TenantId,
    val traceId: String,
    val deadline: Instant,
    val idempotencyKey: String? = null,
) {
    /** [now]時点での残余タイムアウト予算。締切超過時はゼロ（負値にはしない）。 */
    fun remaining(now: Instant): Duration {
        val left = Duration.between(now, deadline)
        return if (left.isNegative) Duration.ZERO else left
    }

    companion object {
        @Suppress("LongParameterList")
        fun start(
            requestId: RequestId,
            tenantId: TenantId,
            traceId: String,
            now: Instant,
            timeoutBudget: Duration,
            idempotencyKey: String? = null,
        ): ExecutionContext = ExecutionContext(requestId, tenantId, traceId, now.plus(timeoutBudget), idempotencyKey)
    }
}
