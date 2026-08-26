package apap.cost

import apap.domain.event.BudgetPeriodReset
import apap.domain.event.CostThresholdExceeded
import apap.domain.model.cost.Budget
import apap.domain.model.cost.PriceBook
import apap.domain.model.cost.PriceEntry
import apap.domain.model.cost.RecurringPeriodType
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.CanonicalResponse
import apap.domain.model.execution.GenerationParams
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.Cost
import apap.domain.model.vo.FinishReason
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.testkit.inmemory.InMemoryBudgetRepository
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import apap.testkit.inmemory.InMemoryPriceBookRepository
import apap.testkit.inmemory.InMemoryUsageRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class DefaultCostEngineTest {
    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val ids = InMemoryIdGenerator()
    private val events = InMemoryDomainEventPublisher()
    private val priceBookRepository = InMemoryPriceBookRepository()
    private val budgetRepository = InMemoryBudgetRepository()
    private val usageRepository = InMemoryUsageRepository()
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA2")
    private val capabilityId = CapabilityId("chat_completion")

    private fun engine(config: CostEngineConfig = CostEngineConfig()): DefaultCostEngine =
        DefaultCostEngine(priceBookRepository, budgetRepository, usageRepository, clock, ids, events, config)

    private fun priceEntry(
        inputPer1k: String = "1.00",
        outputPer1k: String = "1.00",
    ) = PriceEntry(
        modelId = modelId,
        inputPer1k = Money(BigDecimal(inputPer1k), "USD"),
        outputPer1k = Money(BigDecimal(outputPer1k), "USD"),
        period = Period(Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2030-01-01T00:00:00Z")),
    )

    private fun request(): CanonicalRequest =
        CanonicalRequest(
            requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FA3"),
            tenantId = tenantId,
            principal = "user-1",
            capabilityId = capabilityId,
            input = listOf(ContentPart.Text("hello")),
            params = GenerationParams(),
            timeoutBudget = Duration.ofSeconds(30),
            traceId = "trace-1",
        )

    private fun response(cost: Cost): CanonicalResponse =
        CanonicalResponse(
            responseId = "resp-1",
            requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FA3"),
            output = listOf(ContentPart.Text("hi")),
            finishReason = FinishReason.COMPLETED,
            usage = Usage.of(TokenCount(100), TokenCount(100)),
            cost = cost,
            resolvedProvider = providerId,
            resolvedModel = modelId,
        )

    @Test
    fun `estimate throws PriceEntryNotFoundException for an unpriced model`() {
        val cost = engine()
        assertThrows(PriceEntryNotFoundException::class.java) {
            cost.estimate(providerId, modelId, ProcessedPrompt(input = listOf(ContentPart.Text("hi"))))
        }
    }

    @Test
    fun `calculate throws PriceEntryNotFoundException for an unpriced model`() {
        val cost = engine()
        assertThrows(PriceEntryNotFoundException::class.java) {
            cost.calculate(Usage.of(TokenCount(1), TokenCount(1)), modelId)
        }
    }

    @Test
    fun `estimate and calculate use the registered PriceEntry`() {
        priceBookRepository.save(PriceBook("book-1", listOf(priceEntry(inputPer1k = "1.00", outputPer1k = "2.00"))))
        val cost = engine(CostEngineConfig(representativeOutputTokens = 500))
        val prompt = ProcessedPrompt(input = listOf(ContentPart.Text("hi")), estimatedTokens = TokenCount(1000))

        val estimated = cost.estimate(providerId, modelId, prompt)
        assertEquals(Money(BigDecimal("2.000000"), "USD"), estimated)

        val calculated = cost.calculate(Usage.of(TokenCount(1000), TokenCount(500)), modelId)
        assertEquals(Money(BigDecimal("2.000000"), "USD"), calculated.amount)
    }

    @Test
    fun `record appends a UsageRecord for the request`() {
        priceBookRepository.save(PriceBook("book-1", listOf(priceEntry())))
        val cost = engine()
        cost.record(request(), response(cost.calculate(Usage.of(TokenCount(100), TokenCount(100)), modelId)), 42)

        val period = Period(Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2030-01-01T00:00:00Z"))
        val aggregate = usageRepository.aggregate(tenantId, period, emptyList())
        assertEquals(1, aggregate.single().requestCount)
    }

    private fun budget(limit: String = "10.00"): Budget =
        Budget(
            budgetId = "budget-1",
            tenantId = tenantId,
            periodType = RecurringPeriodType.DAILY,
            currentWindow = Period(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z")),
            limit = Money(BigDecimal(limit), "USD"),
            consumed = Money.zero("USD"),
        )

    @Test
    fun `crossing the 80 percent threshold fires CostThresholdExceeded exactly once`() {
        budgetRepository.save(budget(limit = "10.00"))
        val cost = engine()
        val eightyPercentCost = Cost(Money(BigDecimal("8.00"), "USD"))

        cost.record(request(), response(eightyPercentCost), 1)
        val fired = events.publishedEvents.filterIsInstance<CostThresholdExceeded>()
        assertEquals(1, fired.size)
        assertEquals(80, fired.single().threshold)

        // A further small consumption that does not cross 100% must not re-fire 80%.
        cost.record(request(), response(Cost(Money(BigDecimal("0.10"), "USD"))), 1)
        assertEquals(1, events.publishedEvents.filterIsInstance<CostThresholdExceeded>().size)
    }

    @Test
    fun `crossing 100 percent fires the 100 threshold in addition to 80`() {
        budgetRepository.save(budget(limit = "10.00"))
        val cost = engine()
        cost.record(request(), response(Cost(Money(BigDecimal("11.00"), "USD"))), 1)

        val fired = events.publishedEvents.filterIsInstance<CostThresholdExceeded>().map { it.threshold }
        assertEquals(listOf(80, 100), fired.sorted())
    }

    @Test
    fun `checkBudget reflects consumption ratio`() {
        budgetRepository.save(budget(limit = "10.00"))
        val cost = engine()
        assertEquals(BudgetStatus.OK, cost.checkBudget(tenantId))

        cost.record(request(), response(Cost(Money(BigDecimal("9.00"), "USD"))), 1)
        assertEquals(BudgetStatus.WARNING, cost.checkBudget(tenantId))

        cost.record(request(), response(Cost(Money(BigDecimal("2.00"), "USD"))), 1)
        assertEquals(BudgetStatus.EXCEEDED, cost.checkBudget(tenantId))
    }

    @Test
    fun `budget resets and re-fires BudgetPeriodReset after crossing a period boundary`() {
        budgetRepository.save(budget(limit = "10.00"))
        val cost = engine()
        cost.record(request(), response(Cost(Money(BigDecimal("9.00"), "USD"))), 1)
        assertEquals(BudgetStatus.WARNING, cost.checkBudget(tenantId))

        clock.advanceTo(Instant.parse("2026-01-02T00:00:01Z"))
        assertEquals(BudgetStatus.OK, cost.checkBudget(tenantId))
        assertTrue(events.publishedEvents.filterIsInstance<BudgetPeriodReset>().isNotEmpty())

        // The reset budget can cross 80% again without being suppressed by the previous period's firing.
        cost.record(request(), response(Cost(Money(BigDecimal("9.00"), "USD"))), 1)
        assertEquals(2, events.publishedEvents.filterIsInstance<CostThresholdExceeded>().size)
    }
}
