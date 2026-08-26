package apap.cost

import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.CanonicalResponse
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.Cost
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.Usage

/** 03_基本設計.md 3.3.6 `CostEngine.checkBudget`の戻り値。 */
enum class BudgetStatus { OK, WARNING, EXCEEDED }

/**
 * タスク要求「単価未登録のModelはルーティング候補にできない」の検出結果。[CostEngine.estimate]/
 * [CostEngine.calculate]が対象Modelの[apap.domain.model.cost.PriceEntry]をPriceBookRepositoryから
 * 見つけられない場合に送出する。
 */
class PriceEntryNotFoundException(
    val modelId: ModelId,
) : Exception("No PriceEntry found for modelId=${modelId.value} (unpriced models cannot be costed)")

/**
 * 03_基本設計.md 3.3.6 `CostEngine`: 単価表管理・コスト算出・Budget監視・Cost-aware Routing用スコア提供。
 * 既定実装は[DefaultCostEngine]（[apap.cost.quota.QuotaManager]は別インターフェースで実装対象）。
 */
interface CostEngine {
    /** @throws PriceEntryNotFoundException [modelId]に有効な単価が登録されていない場合。 */
    fun estimate(
        providerId: ProviderId,
        modelId: ModelId,
        prompt: ProcessedPrompt,
    ): Money

    /** @throws PriceEntryNotFoundException [modelId]に有効な単価が登録されていない場合。 */
    fun calculate(
        usage: Usage,
        modelId: ModelId,
    ): Cost

    /**
     * 実行成功時の使用量記録・Budget消費・閾値監視。[CanonicalResponse]自体は
     * tenantId/capabilityId/durationMsを持たないため、呼び出し元の[CanonicalRequest]を併せて渡す
     * （要件充足に影響しない実装判断のためADR化せず根拠をここに残す、`ContextManager.build`の
     * シグネチャ拡張と同じ判断）。
     */
    fun record(
        request: CanonicalRequest,
        response: CanonicalResponse,
        durationMs: Long,
    )

    fun checkBudget(tenantId: TenantId): BudgetStatus
}
