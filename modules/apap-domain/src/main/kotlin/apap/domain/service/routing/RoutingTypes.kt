package apap.domain.service.routing

import apap.domain.model.execution.CbState
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.Score

/**
 * 02_システム仕様.md 2.5.2の重み `w_cost/w_latency/w_avail/w_priority`。合計は呼び出し側の責務
 * （既定4値は[RoutingDomainService.resolveWeights]が2.5.2の表通りに提供する）。
 */
data class RoutingWeights(
    val cost: Double,
    val latency: Double,
    val availability: Double,
    val priority: Double,
) {
    init {
        listOf(cost, latency, availability, priority).forEach {
            require(it in 0.0..1.0) { "weight must be within 0.0..1.0: $it" }
        }
    }
}

/**
 * RoutingDomainServiceが操作する候補1件。Health/CB/Quota/Policy許諾等、I/Oを要する事実は
 * すべて呼び出し側（Capability Resolver / Health Store / Quota Manager等）が解決済みの値として
 * 供給する（Domain Serviceは純粋関数として、この事実の集合からフィルタ・スコアのみを行う）。
 */
data class Candidate(
    val providerId: ProviderId,
    val modelId: ModelId,
    val providerStatus: ProviderStatus,
    val modelStatus: ModelStatus,
    val isCanaryEligible: Boolean = false,
    val cbState: CbState,
    val health: ProviderHealthStatus,
    val supportedRegions: Set<Region>,
    val estimatedCost: Money,
    val p90LatencyMs: Double,
    val successRate: Double,
    val providerPriority: Int,
    val modelPriority: Int,
    val hasPermission: Boolean,
    val quotaRemaining: Boolean,
) {
    init {
        require(p90LatencyMs >= 0.0) { "p90LatencyMs must not be negative: $p90LatencyMs" }
        require(successRate in 0.0..1.0) { "successRate must be within 0.0..1.0: $successRate" }
        require(providerPriority in MIN_PRIORITY..MAX_PRIORITY) {
            "providerPriority must be within 1..100: $providerPriority"
        }
        require(modelPriority in MIN_PRIORITY..MAX_PRIORITY) { "modelPriority must be within 1..100: $modelPriority" }
    }

    /** Sticky補正対象判定やChain構築での重複排除に用いる安定キー。 */
    val key: String get() = "${providerId.value}:${modelId.value}"

    companion object {
        private const val MIN_PRIORITY = 1
        private const val MAX_PRIORITY = 100
    }
}

data class ScoredCandidate(
    val candidate: Candidate,
    val score: Score,
)

/** 02_システム仕様.md 2.5.2 step 6: Fallback Chain。スコア降順、既定3段。 */
data class FallbackChain(
    val candidates: List<Candidate>,
) {
    init {
        require(candidates.isNotEmpty()) { "FallbackChain must not be empty" }
    }
}
