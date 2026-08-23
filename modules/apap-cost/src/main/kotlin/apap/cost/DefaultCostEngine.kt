package apap.cost

import apap.domain.event.BudgetPeriodReset
import apap.domain.event.CostThresholdExceeded
import apap.domain.event.EventMetadata
import apap.domain.model.cost.Budget
import apap.domain.model.cost.UsageRecord
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.CanonicalResponse
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.Cost
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.domain.port.BudgetRepository
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import apap.domain.port.PriceBookRepository
import apap.domain.port.UsageRepository
import apap.domain.service.cost.CostCalculationService
import apap.domain.service.cost.PeriodWindowService
import java.math.BigDecimal
import java.math.RoundingMode

/** CLAUDE.md不変条件7: 既定値は設定可能。 */
data class CostEngineConfig(
    /**
     * [DefaultCostEngine.estimate]は実行前の見積りであり出力トークン数は未知なため、代表値として使う
     * （実prompt依存の正確な値は execution後の[DefaultCostEngine.record]/[DefaultCostEngine.calculate]で
     * 確定する）。
     */
    val representativeOutputTokens: Int = DEFAULT_REPRESENTATIVE_OUTPUT_TOKENS,
) {
    init {
        require(representativeOutputTokens >= 0) {
            "representativeOutputTokens must not be negative: $representativeOutputTokens"
        }
    }

    private companion object {
        const val DEFAULT_REPRESENTATIVE_OUTPUT_TOKENS = 1000
    }
}

/**
 * [CostEngine]の既定実装。02_システム仕様.md 2.14周辺 / 04_ドメイン設計.md 4.3.5。
 *
 * Budget監視（[record]/[checkBudget]）は、[apap.cost.quota.DefaultQuotaManager]のTenantLedgerと
 * 同じ「期間境界を跨いだら台帳をリセットする」自己修復パターンを踏襲する（[currentBudget]）。
 * 閾値超過（[CostThresholdExceeded]）は、リセットで単調増加に戻る`Budget.consumed`の消費前後の
 * 比率を比較し、新たに跨いだ閾値のみを発火することで重複発火を防ぐ（[thresholdsCrossed]）。
 */
@Suppress("TooManyFunctions", "LongParameterList")
class DefaultCostEngine(
    private val priceBookRepository: PriceBookRepository,
    private val budgetRepository: BudgetRepository,
    private val usageRepository: UsageRepository,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val eventPublisher: DomainEventPublisher,
    private val config: CostEngineConfig = CostEngineConfig(),
) : CostEngine {
    override fun estimate(
        providerId: ProviderId,
        modelId: ModelId,
        prompt: ProcessedPrompt,
    ): Money {
        val priceEntry = priceEntryFor(modelId)
        val usage = Usage.of(prompt.estimatedTokens, TokenCount(config.representativeOutputTokens), estimated = true)
        return CostCalculationService.calculate(usage, priceEntry).amount
    }

    override fun calculate(
        usage: Usage,
        modelId: ModelId,
    ): Cost = CostCalculationService.calculate(usage, priceEntryFor(modelId))

    override fun record(
        request: CanonicalRequest,
        response: CanonicalResponse,
        durationMs: Long,
    ) {
        usageRepository.append(
            UsageRecord(
                usageId = idGenerator.newId(),
                requestId = response.requestId,
                tenantId = request.tenantId,
                capabilityId = request.capabilityId,
                providerId = response.resolvedProvider,
                modelId = response.resolvedModel,
                usage = response.usage,
                cost = response.cost,
                durationMs = durationMs,
                status = response.finishReason.name,
                occurredAt = clock.now(),
            ),
        )
        budgetRepository.findByTenant(request.tenantId).forEach { budget ->
            applyCost(budget, response.cost.amount, request.traceId)
        }
    }

    override fun checkBudget(tenantId: TenantId): BudgetStatus {
        val statuses = budgetRepository.findByTenant(tenantId).map { statusFor(currentBudget(it, SYSTEM_TRACE_ID)) }
        return statuses.maxOrNull() ?: BudgetStatus.OK
    }

    private fun priceEntryFor(modelId: ModelId) =
        priceBookRepository.findCurrentEntry(modelId, clock.now()) ?: throw PriceEntryNotFoundException(modelId)

    private fun applyCost(
        budget: Budget,
        additional: Money,
        traceId: String,
    ) {
        val windowed = currentBudget(budget, traceId)
        val updated = windowed.consume(additional)
        thresholdsCrossed(windowed, updated).forEach { threshold ->
            eventPublisher.publish(
                CostThresholdExceeded(
                    meta = eventMeta(updated.tenantId, updated.budgetId, traceId),
                    tenantId = updated.tenantId,
                    budgetId = updated.budgetId,
                    threshold = threshold,
                    consumed = updated.consumed,
                ),
            )
        }
        budgetRepository.save(updated)
    }

    /** FR-OBS-005: 期間境界を跨いでいれば台帳（Budget）をリセットし、`BudgetPeriodReset`を発行する。 */
    private fun currentBudget(
        budget: Budget,
        traceId: String,
    ): Budget {
        val window = PeriodWindowService.windowContaining(clock.now(), budget.periodType)
        if (window == budget.currentWindow) return budget
        val reset = budget.resetForNewPeriod(window)
        budgetRepository.save(reset)
        eventPublisher.publish(BudgetPeriodReset(eventMeta(reset.tenantId, reset.budgetId, traceId), reset.budgetId))
        return reset
    }

    private fun thresholdsCrossed(
        before: Budget,
        after: Budget,
    ): List<Int> {
        val beforeRatio = ratioPercent(before)
        val afterRatio = ratioPercent(after)
        return after.thresholds.filter { beforeRatio < it && afterRatio >= it }.sorted()
    }

    private fun statusFor(budget: Budget): BudgetStatus {
        val ratio = ratioPercent(budget)
        val warningThreshold = budget.thresholds.filter { it < FULL_PERCENT }.minOrNull() ?: DEFAULT_WARNING_THRESHOLD
        return when {
            ratio >= FULL_PERCENT -> BudgetStatus.EXCEEDED
            ratio >= warningThreshold -> BudgetStatus.WARNING
            else -> BudgetStatus.OK
        }
    }

    private fun ratioPercent(budget: Budget): Double {
        if (budget.limit.amount.signum() == 0) {
            return if (budget.consumed.amount.signum() > 0) Double.MAX_VALUE else 0.0
        }
        return budget.consumed.amount
            .divide(budget.limit.amount, RATIO_SCALE, RoundingMode.HALF_UP)
            .multiply(BigDecimal(FULL_PERCENT))
            .toDouble()
    }

    private fun eventMeta(
        tenantId: TenantId,
        aggregateId: String,
        traceId: String,
    ): EventMetadata =
        EventMetadata(
            eventId = idGenerator.newId(),
            occurredAt = clock.now(),
            traceId = traceId,
            tenantId = tenantId,
            aggregateId = aggregateId,
            version = 0,
        )

    private companion object {
        const val RATIO_SCALE = 6
        const val FULL_PERCENT = 100
        const val DEFAULT_WARNING_THRESHOLD = 80
        const val SYSTEM_TRACE_ID = "system"
    }
}
