package apap.domain.model.execution

import apap.domain.model.IllegalStateTransitionException
import apap.domain.model.vo.CbKey
import java.time.Instant

/** 09_状態遷移図.md 9.3。 */
enum class CbState { CLOSED, OPEN, HALF_OPEN }

class IllegalCbStateTransitionException(
    from: CbState,
    to: CbState,
) : IllegalStateTransitionException("Illegal CircuitBreakerState transition: $from -> $to")

/** 02_システム仕様.md 2.13: 直近30秒スライディングウィンドウの試行/失敗数。 */
data class WindowStats(
    val requestCount: Int,
    val failureCount: Int,
) {
    init {
        require(requestCount >= 0) { "requestCount must not be negative: $requestCount" }
        require(failureCount in 0..requestCount) {
            "failureCount must be within 0..requestCount: failureCount=$failureCount, requestCount=$requestCount"
        }
    }

    val failureRate: Double get() = if (requestCount == 0) 0.0 else failureCount.toDouble() / requestCount

    companion object {
        val EMPTY = WindowStats(0, 0)
    }
}

/**
 * 04_ドメイン設計.md 4.3.5 CircuitBreakerState Aggregate（Root）。
 * cbKey(provider,model)単位。遷移は09_状態遷移図.md 9.3のみ。
 */
data class CircuitBreakerState(
    val cbKey: CbKey,
    val state: CbState = CbState.CLOSED,
    val windowStats: WindowStats = WindowStats.EMPTY,
    val openedAt: Instant? = null,
    val openCount: Int = 0,
) {
    fun transitionTo(
        target: CbState,
        at: Instant,
    ): CircuitBreakerState {
        val allowed = ALLOWED_TRANSITIONS[state].orEmpty()
        if (target !in allowed) {
            throw IllegalCbStateTransitionException(state, target)
        }
        val newOpenedAt =
            when (target) {
                CbState.OPEN -> at
                CbState.CLOSED -> null
                CbState.HALF_OPEN -> openedAt
            }
        val newOpenCount = if (target == CbState.OPEN) openCount + 1 else openCount
        return copy(state = target, openedAt = newOpenedAt, openCount = newOpenCount)
    }

    companion object {
        // 9.3: CLOSED->OPEN(req>=10かつ失敗率>=50%)、OPEN->HALF_OPEN(open時間経過)、
        //      HALF_OPEN->CLOSED(3連続成功)、HALF_OPEN->OPEN(1失敗)
        private val ALLOWED_TRANSITIONS: Map<CbState, Set<CbState>> =
            mapOf(
                CbState.CLOSED to setOf(CbState.OPEN),
                CbState.OPEN to setOf(CbState.HALF_OPEN),
                CbState.HALF_OPEN to setOf(CbState.CLOSED, CbState.OPEN),
            )
    }
}
