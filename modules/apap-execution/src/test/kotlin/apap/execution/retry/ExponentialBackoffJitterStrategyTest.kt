package apap.execution.retry

import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.NormalizedError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** 02_システム仕様.md 2.11 / 05_シーケンス設計.md 5.7。 */
class ExponentialBackoffJitterStrategyTest {
    private val retryableError =
        NormalizedError(
            code = ErrorCode.PROVIDER_ERROR,
            category = AdapterErrorCategory.TRANSIENT,
            message = "boom",
            retryable = true,
            fallbackable = true,
            cbRecordable = true,
        )
    private val nonRetryableError = retryableError.copy(retryable = false)

    @Test
    fun `returns null for a non-retryable error`() {
        val strategy = ExponentialBackoffJitterStrategy()
        assertNull(strategy.nextDelay(1, nonRetryableError, retryAfter = null))
    }

    @Test
    fun `delay for attempt 1 is within 0 and base backoff`() {
        val config = RetryConfig(baseBackoffMs = 200, backoffCapMs = 5000)
        val strategy = ExponentialBackoffJitterStrategy(config, random = { 0.999 })
        val delay = strategy.nextDelay(1, retryableError, retryAfter = null)
        assertTrue(delay != null && delay.toMillis() in 0..200)
    }

    @Test
    fun `delay for attempt 2 doubles the exponential base before capping`() {
        val config = RetryConfig(baseBackoffMs = 200, backoffCapMs = 5000)
        val strategy = ExponentialBackoffJitterStrategy(config, random = { 1.0 })
        // attempt=2 -> exponent=1 -> 200*2=400ms cap window
        val delay = strategy.nextDelay(2, retryableError, retryAfter = null)
        assertEquals(400L, delay?.toMillis())
    }

    @Test
    fun `exponential delay is capped at backoffCapMs`() {
        val config = RetryConfig(baseBackoffMs = 200, backoffCapMs = 1000)
        val strategy = ExponentialBackoffJitterStrategy(config, random = { 1.0 })
        // attempt=10 -> 200*2^9 would be huge; must cap to 1000ms
        val delay = strategy.nextDelay(10, retryableError, retryAfter = null)
        assertEquals(1000L, delay?.toMillis())
    }

    @Test
    fun `Retry-After header takes priority over exponential backoff`() {
        val config = RetryConfig(baseBackoffMs = 200, backoffCapMs = 5000, retryAfterCapMs = 10_000)
        val strategy = ExponentialBackoffJitterStrategy(config)
        val delay = strategy.nextDelay(1, retryableError, retryAfter = Duration.ofSeconds(2))
        assertEquals(2000L, delay?.toMillis())
    }

    @Test
    fun `Retry-After header is capped at retryAfterCapMs`() {
        val config = RetryConfig(retryAfterCapMs = 10_000)
        val strategy = ExponentialBackoffJitterStrategy(config)
        val delay = strategy.nextDelay(1, retryableError, retryAfter = Duration.ofSeconds(30))
        assertEquals(10_000L, delay?.toMillis())
    }
}
