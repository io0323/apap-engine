package apap.execution.retry

import apap.domain.model.vo.NormalizedError
import java.time.Duration
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** 02_システム仕様.md 2.11。CLAUDE.md不変条件7に従い設定可能、既定値は設計書と一致させる。 */
data class RetryConfig(
    val maxAttempts: Int = 3,
    val baseBackoffMs: Long = 200,
    val backoffCapMs: Long = 5000,
    val retryAfterCapMs: Long = 10000,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1: $maxAttempts" }
        require(baseBackoffMs > 0) { "baseBackoffMs must be positive: $baseBackoffMs" }
        require(backoffCapMs >= baseBackoffMs) {
            "backoffCapMs must be >= baseBackoffMs: cap=$backoffCapMs, base=$baseBackoffMs"
        }
        require(retryAfterCapMs > 0) { "retryAfterCapMs must be positive: $retryAfterCapMs" }
    }
}

/** 03_基本設計.md 3.3.5 `RetryStrategy`（差替可能点、16_拡張ポイント.md 16.5）。 */
interface RetryStrategy {
    /** @return 次試行までの待機時間。nullはリトライしない。 */
    fun nextDelay(
        attempt: Int,
        error: NormalizedError,
        retryAfter: Duration?,
    ): Duration?
}

/**
 * 02_システム仕様.md 2.11既定Strategy: 指数バックオフ + full jitter
 * （`sleep = random(0, min(cap, base * 2^(attempt-1)))`、AWS Architecture Blogの標準的な定義）。
 * Retry-Afterヘッダがあれば優先（上限[RetryConfig.retryAfterCapMs]）。
 */
class ExponentialBackoffJitterStrategy(
    private val config: RetryConfig = RetryConfig(),
    private val random: () -> Double = { Random.nextDouble() },
) : RetryStrategy {
    @Suppress("ReturnCount")
    override fun nextDelay(
        attempt: Int,
        error: NormalizedError,
        retryAfter: Duration?,
    ): Duration? {
        if (!error.retryable) return null
        retryAfter?.let {
            val cappedMs = min(it.toMillis(), config.retryAfterCapMs)
            return Duration.ofMillis(cappedMs)
        }
        val exponentialMs = config.baseBackoffMs * 2.0.pow((attempt - 1).coerceAtLeast(0))
        val cappedMs = min(exponentialMs, config.backoffCapMs.toDouble())
        val jitteredMs = (random() * cappedMs).toLong()
        return Duration.ofMillis(jitteredMs)
    }
}
