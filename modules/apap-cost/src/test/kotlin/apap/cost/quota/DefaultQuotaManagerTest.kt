package apap.cost.quota

import apap.domain.model.cost.QuotaLimits
import apap.domain.model.cost.QuotaPolicy
import apap.domain.model.cost.RecurringPeriodType
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** タスク要件item7: 決済漏れ0の保証（P0で保留していたC2/U16の解消）。 */
class DefaultQuotaManagerTest {
    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val events = InMemoryDomainEventPublisher()
    private val ids = InMemoryIdGenerator()
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA2")

    private fun manager(config: QuotaManagerConfig = QuotaManagerConfig()): DefaultQuotaManager =
        DefaultQuotaManager(ids, clock, events, config)

    private fun policy(limits: QuotaLimits): QuotaPolicy =
        QuotaPolicy(
            "quota-1",
            tenantId,
            "tenant",
            RecurringPeriodType.DAILY,
            limits,
        )

    private fun reserve(
        qm: QuotaManager,
        tokens: Int = 1,
        cost: Money = Money.zero("USD"),
        policy: QuotaPolicy? = null,
        trace: String = "trace",
    ): Reservation =
        qm.checkAndReserve(
            tenantId,
            providerId,
            modelId,
            TokenCount(tokens),
            cost,
            policy,
            trace,
            clock.now(),
        )

    @Test
    fun `success path commits with actual usage and cost`() {
        val qm = manager()
        val reservation = reserve(qm, tokens = 100)
        qm.commit(reservation, Usage.of(TokenCount(40), TokenCount(60)), Money.zero("USD"))
        // committing twice must fail: settlement is not repeatable
        assertThrows(IllegalStateException::class.java) {
            qm.commit(reservation, Usage.of(TokenCount(1), TokenCount(1)), Money.zero("USD"))
        }
    }

    @Test
    fun `failure path releases the reservation`() {
        val qm = manager()
        val reservation = reserve(qm, tokens = 100)
        qm.release(reservation)
        assertThrows(IllegalStateException::class.java) { qm.release(reservation) }
    }

    @Test
    fun `streaming interruption commits with partially received usage`() {
        val qm = manager()
        val reservation = reserve(qm, tokens = 1000)
        // Partial usage received before disconnect is committed, not released and not a full commit
        // of the estimate.
        qm.commit(reservation, Usage.of(TokenCount(10), TokenCount(5)), Money.zero("USD"))
    }

    @Test
    fun `cache short-circuit consumes only tenant requests, no reservation created`() {
        val qm = manager()
        val limits = QuotaLimits(requests = 1)
        qm.recordCacheShortCircuit(tenantId, policy(limits))
        // The requests counter is already at 1 (committed directly); a subsequent real reservation
        // for the same tenant must now be rejected by the requests limit.
        assertThrows(QuotaExceededException::class.java) { reserve(qm, policy = policy(limits)) }
    }

    @Test
    fun `TTL expiry releases stale pending reservations and reports them`() {
        val qm = manager(QuotaManagerConfig(reservationTtl = Duration.ofMinutes(1)))
        val limits = QuotaLimits(requests = 1)
        val reservation = reserve(qm, policy = policy(limits))

        // A second reservation attempt is rejected while the first is still pending and unexpired.
        assertThrows(QuotaExceededException::class.java) { reserve(qm, policy = policy(limits)) }

        clock.advanceBy(61)
        val expired = qm.expireStale(clock.now())
        assertEquals(1, expired.size)
        assertEquals(reservation.reservationId, expired.first().reservation.reservationId)
        assertEquals(ReservationStatus.EXPIRED, expired.first().reservation.status)

        // Quota is freed after expiry: a new reservation now succeeds.
        reserve(qm, policy = policy(limits))
    }

    @Test
    fun `expired reservation cannot later be committed or released`() {
        val qm = manager(QuotaManagerConfig(reservationTtl = Duration.ofSeconds(30)))
        val reservation = reserve(qm)
        clock.advanceBy(31)
        qm.expireStale(clock.now())
        assertThrows(IllegalStateException::class.java) {
            qm.commit(reservation, Usage.of(TokenCount(1), TokenCount(1)), Money.zero("USD"))
        }
    }

    @Test
    fun `checkAndReserve lazily sweeps expired reservations before enforcing limits`() {
        val qm = manager(QuotaManagerConfig(reservationTtl = Duration.ofSeconds(10)))
        val limits = QuotaLimits(requests = 1)
        reserve(qm, policy = policy(limits))
        clock.advanceBy(11)
        // Without an external Scheduler calling expireStale, this call must self-heal via lazy sweeping.
        reserve(qm, policy = policy(limits))
    }

    @Test
    fun `tokens and cost limits are enforced independently of requests`() {
        val qm = manager()
        val tokenLimited = policy(QuotaLimits(tokens = 50))
        assertThrows(QuotaExceededException::class.java) { reserve(qm, tokens = 51, policy = tokenLimited) }

        val costLimited = policy(QuotaLimits(cost = Money(BigDecimal("1.00"), "USD")))
        assertThrows(QuotaExceededException::class.java) {
            reserve(qm, cost = Money(BigDecimal("2.00"), "USD"), policy = costLimited)
        }
    }

    @Test
    fun `no leaked reservations across a mixed sequence of settle paths`() {
        val qm = manager(QuotaManagerConfig(reservationTtl = Duration.ofSeconds(5)))
        val limits = QuotaLimits(requests = 4)

        val committed = reserve(qm, policy = policy(limits))
        qm.commit(committed, Usage.of(TokenCount(1), TokenCount(1)), Money.zero("USD"))

        val released = reserve(qm, policy = policy(limits))
        qm.release(released)

        reserve(qm, policy = policy(limits))
        clock.advanceBy(6)
        val expired = qm.expireStale(clock.now())
        assertTrue(expired.isNotEmpty())

        // After commit(1) + release(1) + expire(1), only 1 request should be counted as committed
        // (the released/expired ones must not linger as pending forever). A fresh reservation for
        // the remaining budget (limit=4, 1 committed) must succeed up to the limit.
        reserve(qm, policy = policy(limits))
        reserve(qm, policy = policy(limits))
        reserve(qm, policy = policy(limits))
        assertThrows(QuotaExceededException::class.java) { reserve(qm, policy = policy(limits)) }
    }

    @Test
    fun `quota resets after crossing a period boundary`() {
        // A TTL far longer than the boundary crossing below isolates the reset from TTL self-healing
        // (DefaultQuotaManagerTest's other TTL-expiry tests cover that separate mechanism).
        val qm = manager(QuotaManagerConfig(reservationTtl = Duration.ofDays(30)))
        val limits = QuotaLimits(requests = 1)
        reserve(qm, policy = policy(limits))
        assertThrows(QuotaExceededException::class.java) { reserve(qm, policy = policy(limits)) }

        // Cross the DAILY period boundary (clock starts at 2026-01-01T00:00:00Z).
        clock.advanceTo(Instant.parse("2026-01-02T00:00:01Z"))
        // The previous day's pending/committed requests must no longer count against the limit.
        reserve(qm, policy = policy(limits))
    }

    @Test
    fun `quota is not reset by advancing time within the same period`() {
        val qm = manager()
        val limits = QuotaLimits(requests = 1)
        reserve(qm, policy = policy(limits))
        // Within both the same DAILY window and the default reservation TTL (10min): must not self-heal.
        clock.advanceBy(300)
        assertThrows(QuotaExceededException::class.java) { reserve(qm, policy = policy(limits)) }
    }
}
