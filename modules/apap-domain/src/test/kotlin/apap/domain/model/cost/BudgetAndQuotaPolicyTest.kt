package apap.domain.model.cost

import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class BudgetAndQuotaPolicyTest {
    private val window = Period(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"))

    private fun budget(consumed: BigDecimal = BigDecimal.ZERO) =
        Budget(
            budgetId = "b1",
            tenantId = TenantId(testUlid('A')),
            periodType = RecurringPeriodType.MONTHLY,
            currentWindow = window,
            limit = Money(BigDecimal("100.00"), "USD"),
            consumed = Money(consumed, "USD"),
        )

    @Test
    fun `requires consumed and limit to share currency`() {
        assertThrows(IllegalArgumentException::class.java) {
            budget().copy(consumed = Money(BigDecimal.ZERO, "JPY"))
        }
    }

    @Test
    fun `consume only ever increases consumed`() {
        val consumed1 = budget().consume(Money(BigDecimal("10.00"), "USD"))
        val consumed2 = consumed1.consume(Money(BigDecimal("5.00"), "USD"))
        assertEquals(Money(BigDecimal("15.00"), "USD"), consumed2.consumed)
    }

    @Test
    fun `consume rejects an amount in a different currency than the budget`() {
        assertThrows(IllegalArgumentException::class.java) {
            budget().consume(Money(BigDecimal("10.00"), "JPY"))
        }
    }

    @Test
    fun `resetForNewPeriod is the only way consumed decreases`() {
        val consumed = budget(BigDecimal("50.00"))
        val newWindow = Period(Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z"))
        val reset = consumed.resetForNewPeriod(newWindow)
        assertEquals(Money.zero("USD"), reset.consumed)
    }

    @Test
    fun `rejects thresholds outside 1 to 1000`() {
        assertThrows(IllegalArgumentException::class.java) { budget().copy(thresholds = listOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { budget().copy(thresholds = listOf(1001)) }
    }

    @Test
    fun `QuotaLimits rejects negative requests or tokens`() {
        assertThrows(IllegalArgumentException::class.java) { QuotaLimits(requests = -1) }
        assertThrows(IllegalArgumentException::class.java) { QuotaLimits(tokens = -1) }
    }

    @Test
    fun `QuotaLimits accepts non negative requests and tokens`() {
        val limits = QuotaLimits(requests = 100, tokens = 1_000_000)
        assertEquals(100L, limits.requests)
        assertEquals(1_000_000L, limits.tokens)
    }

    @Test
    fun `QuotaPolicy rejects blank scope`() {
        assertThrows(IllegalArgumentException::class.java) {
            QuotaPolicy("q1", TenantId(testUlid('A')), " ", RecurringPeriodType.DAILY, QuotaLimits())
        }
    }

    @Test
    fun `QuotaPolicy accepts a non-blank scope`() {
        val policy = QuotaPolicy("q1", TenantId(testUlid('A')), "tenant", RecurringPeriodType.DAILY, QuotaLimits())
        assertEquals("tenant", policy.scope)
    }
}
