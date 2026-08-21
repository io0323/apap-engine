package apap.execution.circuitbreaker

import apap.domain.event.CircuitBreakerStateChanged
import apap.domain.event.EventMetadata
import apap.domain.model.execution.CbState
import apap.domain.model.execution.CircuitBreakerState
import apap.domain.model.execution.WindowStats
import apap.domain.model.vo.CbKey
import apap.domain.port.CircuitBreakerStateStore
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.pow

/**
 * 02_システム仕様.md 2.13 / 09_状態遷移図.md 9.3の既定値。CLAUDE.md不変条件7に従い設定可能とし、
 * 既定値は設計書と一致させる。
 */
data class CircuitBreakerConfig(
    val windowSeconds: Long = 30,
    val minRequests: Int = 10,
    val failureRateThreshold: Double = 0.5,
    val halfOpenBaseSeconds: Long = 30,
    val halfOpenCapSeconds: Long = 600,
    val halfOpenMaxConcurrent: Int = 3,
    val closeAfterConsecutiveSuccesses: Int = 3,
) {
    init {
        require(windowSeconds > 0) { "windowSeconds must be positive: $windowSeconds" }
        require(minRequests > 0) { "minRequests must be positive: $minRequests" }
        require(failureRateThreshold in 0.0..1.0) {
            "failureRateThreshold must be within 0.0..1.0: $failureRateThreshold"
        }
        require(halfOpenBaseSeconds > 0) { "halfOpenBaseSeconds must be positive: $halfOpenBaseSeconds" }
        require(halfOpenCapSeconds >= halfOpenBaseSeconds) {
            "halfOpenCapSeconds must be >= halfOpenBaseSeconds: cap=$halfOpenCapSeconds, base=$halfOpenBaseSeconds"
        }
        require(halfOpenMaxConcurrent > 0) { "halfOpenMaxConcurrent must be positive: $halfOpenMaxConcurrent" }
        require(closeAfterConsecutiveSuccesses > 0) {
            "closeAfterConsecutiveSuccesses must be positive: $closeAfterConsecutiveSuccesses"
        }
    }
}

/** 09_状態遷移図.md 9.3注記: 「tryAcquireは即時拒否→Fallback対象」。 */
class CircuitOpenException(
    key: CbKey,
) : Exception("Circuit breaker is OPEN for ${key.providerId.value}:${key.modelId.value}")

/**
 * [CircuitBreaker.tryAcquire]の戻り値。[recordSuccess]/[recordFailure]へ引き渡し、HALF_OPEN時の
 * 並行数カウンタを正しく解放するために使う（どの試行がHALF_OPENの1枠を消費したかを追跡する必要がある
 * ため。3.3.5の疑似コードはPermitの中身を規定していない実装詳細であり、FR/NFR充足には影響しない
 * ためADR化せずここに判断根拠を残す）。
 */
class Permit internal constructor(
    val key: CbKey,
    internal val halfOpen: Boolean,
)

/**
 * 03_基本設計.md 3.3.5 `CircuitBreaker` / ADR-0001（本番既定のin-memory実装はapap-executionの責務）。
 * 状態機械そのもの（不正遷移の拒否）は[CircuitBreakerState.transitionTo]（apap-domain）に委譲し
 * 再実装しない。本クラスは「いつ遷移を試みるか」（30秒スライディングウィンドウの失敗率判定、
 * HALF_OPENへの指数バックオフ待機、HALF_OPEN内の3並行制限、3連続成功判定）という
 * オーケストレーションのみを担う。
 *
 * スライディングウィンドウの生ログ（[attemptLog]）・HALF_OPEN並行数・連続成功数は
 * [CircuitBreakerState]（永続化される集約）には含めない実行時限りのブックキーピングとして
 * このインスタンス内に保持する（永続化対象はスナップショット値のみで十分なため）。
 *
 * 全キー共通のインスタンスロック（`@Synchronized`）でシリアライズする。単一プロセス埋込利用
 * （ADR-0001）が主用途であり、Adapter I/O発生前後のごく短い純メモリ操作のみを保護するため、
 * キー別ロックへの最適化は現時点で行わない。
 */
class CircuitBreaker(
    private val store: CircuitBreakerStateStore,
    private val clock: Clock,
    private val eventPublisher: DomainEventPublisher,
    private val idGenerator: IdGenerator,
    private val config: CircuitBreakerConfig = CircuitBreakerConfig(),
) {
    private data class Attempt(
        val at: Instant,
        val failed: Boolean,
    )

    private val attemptLog = ConcurrentHashMap<CbKey, MutableList<Attempt>>()
    private val halfOpenInFlight = ConcurrentHashMap<CbKey, AtomicInteger>()
    private val halfOpenConsecutiveSuccesses = ConcurrentHashMap<CbKey, AtomicInteger>()

    @Synchronized
    fun tryAcquire(
        key: CbKey,
        traceId: String,
    ): Permit {
        val now = clock.now()
        val state = maybeTransitionToHalfOpen(readOrCreate(key), now, traceId)
        return when (state.state) {
            CbState.OPEN -> throw CircuitOpenException(key)
            CbState.HALF_OPEN -> {
                val counter = halfOpenInFlight.computeIfAbsent(key) { AtomicInteger(0) }
                if (counter.incrementAndGet() > config.halfOpenMaxConcurrent) {
                    counter.decrementAndGet()
                    throw CircuitOpenException(key)
                }
                Permit(key, halfOpen = true)
            }
            CbState.CLOSED -> Permit(key, halfOpen = false)
        }
    }

    @Synchronized
    fun recordSuccess(
        permit: Permit,
        traceId: String,
    ) {
        val now = clock.now()
        recordAttempt(permit.key, now, failed = false)
        if (!permit.halfOpen) return
        halfOpenInFlight[permit.key]?.decrementAndGet()
        val successes = halfOpenConsecutiveSuccesses.computeIfAbsent(permit.key) { AtomicInteger(0) }.incrementAndGet()
        if (successes >= config.closeAfterConsecutiveSuccesses) {
            transition(permit.key, CbState.CLOSED, now, traceId)
        }
    }

    /**
     * [cbRecordable]は02_システム仕様.md 2.11「CB記録」列（[apap.domain.model.vo.NormalizedError.cbRecordable]）。
     * falseな分類（INVALID_REQUEST等）はウィンドウにもHALF_OPENの1失敗判定にも数えない。
     */
    @Synchronized
    fun recordFailure(
        permit: Permit,
        cbRecordable: Boolean,
        traceId: String,
    ) {
        val now = clock.now()
        if (permit.halfOpen) {
            halfOpenInFlight[permit.key]?.decrementAndGet()
            if (cbRecordable) {
                halfOpenConsecutiveSuccesses[permit.key]?.set(0)
                transition(permit.key, CbState.OPEN, now, traceId)
            }
            return
        }
        if (!cbRecordable) return
        recordAttempt(permit.key, now, failed = true)
        val stats = windowStatsFor(permit.key, now)
        if (stats.requestCount >= config.minRequests && stats.failureRate >= config.failureRateThreshold) {
            transition(permit.key, CbState.OPEN, now, traceId)
        }
    }

    fun state(key: CbKey): CbState = store.find(key)?.state ?: CbState.CLOSED

    private fun recordAttempt(
        key: CbKey,
        now: Instant,
        failed: Boolean,
    ) {
        val log = attemptLog.computeIfAbsent(key) { java.util.Collections.synchronizedList(mutableListOf()) }
        synchronized(log) {
            log.add(Attempt(now, failed))
            log.removeAll { it.at.isBefore(now.minusSeconds(config.windowSeconds)) }
        }
    }

    private fun windowStatsFor(
        key: CbKey,
        now: Instant,
    ): WindowStats {
        val log = attemptLog[key] ?: return WindowStats.EMPTY
        val cutoff = now.minusSeconds(config.windowSeconds)
        synchronized(log) {
            val recent = log.filter { !it.at.isBefore(cutoff) }
            return WindowStats(recent.size, recent.count { it.failed })
        }
    }

    private fun readOrCreate(key: CbKey): CircuitBreakerState = store.find(key) ?: CircuitBreakerState(cbKey = key)

    @Suppress("ReturnCount")
    private fun maybeTransitionToHalfOpen(
        state: CircuitBreakerState,
        now: Instant,
        traceId: String,
    ): CircuitBreakerState {
        if (state.state != CbState.OPEN) return state
        val openedAt = state.openedAt ?: return state
        val backoffSeconds =
            min(
                config.halfOpenBaseSeconds * 2.0.pow((state.openCount - 1).coerceAtLeast(0)),
                config.halfOpenCapSeconds.toDouble(),
            ).toLong()
        return if (!now.isBefore(openedAt.plusSeconds(backoffSeconds))) {
            transition(state.cbKey, CbState.HALF_OPEN, now, traceId)
        } else {
            state
        }
    }

    private fun transition(
        key: CbKey,
        target: CbState,
        at: Instant,
        traceId: String,
    ): CircuitBreakerState {
        val current = store.find(key) ?: CircuitBreakerState(cbKey = key)
        if (current.state == target) return current
        val next = current.transitionTo(target, at)
        store.save(next)
        if (target == CbState.CLOSED) {
            attemptLog.remove(key)
            halfOpenConsecutiveSuccesses.remove(key)
            halfOpenInFlight.remove(key)
        }
        eventPublisher.publish(
            CircuitBreakerStateChanged(
                meta =
                    EventMetadata(
                        eventId = idGenerator.newId(),
                        occurredAt = at,
                        traceId = traceId,
                        tenantId = null,
                        aggregateId = "${key.providerId.value}:${key.modelId.value}",
                        version = 0,
                    ),
                cbKey = key,
                from = current.state,
                to = target,
            ),
        )
        return next
    }
}
