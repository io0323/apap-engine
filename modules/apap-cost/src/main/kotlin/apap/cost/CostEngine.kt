package apap.cost

import apap.domain.model.execution.CanonicalResponse
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.Cost
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.Usage
import org.slf4j.LoggerFactory

/** 03_基本設計.md 3.3.6 `CostEngine.checkBudget`の戻り値。 */
enum class BudgetStatus { OK, WARNING, EXCEEDED }

/**
 * 03_基本設計.md 3.3.6 `CostEngine`: 単価表管理・コスト算出・Budget監視・Cost-aware Routing用スコア提供。
 * 本フェーズはCostEngine自体（P7）を対象外とし、apap-executionが依存する口としてこのPortのみを定義する
 * （[apap.cost.quota.QuotaManager]は別インターフェースであり本フェーズで実装対象）。
 */
interface CostEngine {
    val isStub: Boolean get() = false

    fun estimate(
        providerId: ProviderId,
        modelId: ModelId,
        prompt: ProcessedPrompt,
    ): Money

    fun calculate(
        usage: Usage,
        modelId: ModelId,
    ): Cost

    fun record(response: CanonicalResponse)

    fun checkBudget(tenantId: TenantId): BudgetStatus
}

/**
 * P7未着手のためのパススルー実装: 常にゼロコスト・`OK`予算を返し、`record`はno-op。
 * [optedIn]の明示的な`true`指定を必須とし、構築時にWARNログを出す
 * （`apap.prompt.PassthroughPromptEngine`と同じ方針）。
 */
class PassthroughCostEngine(
    optedIn: Boolean,
    private val currency: String = DEFAULT_CURRENCY,
) : CostEngine {
    override val isStub: Boolean = true

    init {
        require(optedIn) {
            "PassthroughCostEngine requires explicit opt-in (optedIn=true). " +
                "Cost calculation/Budget monitoring (P7) is not implemented; every response is costed as zero."
        }
        logger.warn(
            "PassthroughCostEngine is wired in — Cost calculation/Budget monitoring (P7) is not implemented. " +
                "estimate()/calculate() always return zero, checkBudget() always returns OK.",
        )
    }

    override fun estimate(
        providerId: ProviderId,
        modelId: ModelId,
        prompt: ProcessedPrompt,
    ): Money = Money.zero(currency)

    override fun calculate(
        usage: Usage,
        modelId: ModelId,
    ): Cost = Cost(Money.zero(currency))

    override fun record(response: CanonicalResponse) = Unit

    override fun checkBudget(tenantId: TenantId): BudgetStatus = BudgetStatus.OK

    private companion object {
        const val DEFAULT_CURRENCY = "USD"
        val logger = LoggerFactory.getLogger(PassthroughCostEngine::class.java)
    }
}
