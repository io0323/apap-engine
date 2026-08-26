package apap.routing

import apap.domain.model.cost.PriceBook
import apap.domain.model.cost.PriceEntry
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.ProviderId
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryPriceBookRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/** FR-RTE-002: ZeroCostEstimatorに代わりS_costが実効性を持つことの検証。 */
class RealCostEstimatorTest {
    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val priceBookRepository = InMemoryPriceBookRepository()
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1")

    private fun priceEntry(
        inputPer1k: String,
        outputPer1k: String,
    ) = PriceEntry(
        modelId = modelId,
        inputPer1k = Money(BigDecimal(inputPer1k), "USD"),
        outputPer1k = Money(BigDecimal(outputPer1k), "USD"),
        period = Period(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z")),
    )

    @Test
    fun `estimate reflects the currently effective PriceEntry`() {
        priceBookRepository.save(PriceBook("book-1", listOf(priceEntry("1.00", "2.00"))))
        val estimator = RealCostEstimator(priceBookRepository, clock)

        val estimate = estimator.estimate(providerId, modelId)
        // representative tokens default to 1000 input + 1000 output: 1.00*1 + 2.00*1 = 3.00
        assertEquals(Money(BigDecimal("3.000000"), "USD"), estimate)
    }

    @Test
    fun `a cheaper PriceEntry yields a smaller estimate than a more expensive one`() {
        priceBookRepository.save(PriceBook("book-1", listOf(priceEntry("0.10", "0.20"))))
        val cheapEstimator = RealCostEstimator(priceBookRepository, clock)
        val cheapEstimate = cheapEstimator.estimate(providerId, modelId)

        val expensiveBookRepository = InMemoryPriceBookRepository()
        expensiveBookRepository.save(PriceBook("book-2", listOf(priceEntry("5.00", "10.00"))))
        val expensiveEstimator = RealCostEstimator(expensiveBookRepository, clock)
        val expensiveEstimate = expensiveEstimator.estimate(providerId, modelId)

        assertTrue(cheapEstimate != null && expensiveEstimate != null && cheapEstimate < expensiveEstimate)
    }

    /** ADR-0021: 単価未登録はペナルティではなく`null`（[CandidateFactory]がCandidate自体を除外する）。 */
    @Test
    fun `an unpriced model returns null instead of a penalty`() {
        val estimator = RealCostEstimator(priceBookRepository, clock)
        val estimate = estimator.estimate(providerId, modelId)
        assertEquals(null, estimate)
    }
}
