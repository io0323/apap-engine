package apap.domain.service.cost

import apap.domain.model.cost.PriceEntry
import apap.domain.model.vo.Cost
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class CostCalculationServiceTest {
    private val priceEntry =
        PriceEntry(
            modelId = ModelId(testUlid('A')),
            inputPer1k = Money(BigDecimal("1.000000"), "USD"),
            outputPer1k = Money(BigDecimal("2.000000"), "USD"),
            period = Period(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z")),
        )

    @Test
    fun `calculates input and output cost per 1k tokens`() {
        val usage = Usage.of(TokenCount(1000), TokenCount(500))
        val cost = CostCalculationService.calculate(usage, priceEntry)
        assertEquals(Money(BigDecimal("1.000000"), "USD"), cost.breakdown.getValue("input"))
        assertEquals(Money(BigDecimal("1.000000"), "USD"), cost.breakdown.getValue("output"))
        assertEquals(Money(BigDecimal("2.000000"), "USD"), cost.amount)
    }

    @Test
    fun `prorate scales breakdown and amount consistently`() {
        val usage = Usage.of(TokenCount(1000), TokenCount(1000))
        val cost = CostCalculationService.calculate(usage, priceEntry)
        val prorated = CostCalculationService.prorate(cost, 0.5)
        assertEquals(Money(BigDecimal("1.500000"), "USD"), prorated.amount)
        assertEquals(prorated.breakdown.values.fold(Money.zero("USD")) { acc, m -> acc + m }, prorated.amount)
    }

    @Test
    fun `prorate rejects a fraction outside 0 to 1`() {
        val cost = CostCalculationService.calculate(Usage.of(TokenCount(1), TokenCount(1)), priceEntry)
        assertThrows(IllegalArgumentException::class.java) {
            CostCalculationService.prorate(cost, 1.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CostCalculationService.prorate(cost, -0.1)
        }
    }

    @Test
    fun `prorate scales a breakdown-less Cost directly on amount`() {
        val cost = Cost(Money(BigDecimal("10.00"), "USD"))
        val prorated = CostCalculationService.prorate(cost, 0.25)
        assertEquals(Money(BigDecimal("2.500000"), "USD"), prorated.amount)
        assert(prorated.breakdown.isEmpty())
    }
}
