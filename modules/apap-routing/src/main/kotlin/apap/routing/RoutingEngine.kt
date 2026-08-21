package apap.routing

import apap.domain.model.routing.PolicyScope
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.RequestId
import apap.domain.port.PolicyRepository
import apap.domain.service.routing.FallbackChain
import apap.domain.service.routing.PolicyResolutionService
import apap.domain.service.routing.RoutingDomainService
import apap.domain.service.routing.RoutingHardFilters
import apap.domain.service.routing.RoutingWeights
import apap.domain.service.routing.ScoredCandidate
import apap.routing.spi.LoadBalancer
import apap.routing.spi.RoutingStrategy
import apap.routing.spi.WeightedRoundRobinLoadBalancer
import apap.routing.spi.WeightedScoreRoutingStrategy
import kotlin.random.Random

class NoCandidateAvailableException(
    capabilityId: CapabilityId,
) : Exception("No routing candidate available for capability: ${capabilityId.value}")

/**
 * 02_システム仕様.md 2.5.2 step7: RoutingDecision{候補一覧, スコア, 適用Policy ID, 選択理由}。
 * Auditへの実際の永続化（`AuditRecord.routingDecision`への埋込）は呼び出し側（Execution Engine、
 * 本モジュールの範囲外）の責務とする（`AuditRecord`はrequestId/usage/cost/duration等、Routing単独
 * では埋められないフィールドを多数持つため）。[toAuditSummary]はその埋込用の要約文字列を提供する。
 */
data class RoutingDecision(
    val chain: FallbackChain,
    val scoredCandidates: List<ScoredCandidate>,
    val appliedPolicyId: String?,
    val reason: String,
    /**
     * S_cost算出に[ZeroCostEstimator]（スタブ、常にゼロを返す）が使われたかどうか。
     * `true`の間はコストに基づくスコア差別化が実質無効であることを示す——CostEstimatorが
     * apap-cost実装に差し替わるまで、この決定を「コスト最適化済み」と読んではならない。
     * 監査記録・テストの双方から構造的に検知できるよう、reason文字列への埋め込みだけに頼らず
     * 独立フィールドとして持つ。
     */
    val costEstimationStubbed: Boolean,
) {
    fun toAuditSummary(): String {
        val candidateSummary = chain.candidates.joinToString(" -> ") { it.key }
        val stubNote = if (costEstimationStubbed) " [cost-estimation-stubbed]" else ""
        return "policy=$appliedPolicyId; chain=$candidateSummary; reason=$reason$stubNote"
    }
}

/**
 * 03_基本設計.md 3.3.4 `RoutingEngine`: 02_システム仕様.md 2.5.2の手順
 * （候補抽出 → ハードフィルタ(a〜g) → スコアリング → Sticky補正 → LoadBalancing → Chain構築 →
 * RoutingDecision記録）をこの順で実行する。判断ロジックは[RoutingHardFilters]/[RoutingDomainService]/
 * [PolicyResolutionService]へ委譲し、本クラスは候補データ収集（[CandidateFactory]）・Policy取得・
 * RoutingDecision組立といった周辺のみを担当する。
 */
class RoutingEngine(
    private val candidateFactory: CandidateFactory,
    private val policyRepository: PolicyRepository,
    private val routingStrategy: RoutingStrategy = WeightedScoreRoutingStrategy(),
    private val loadBalancer: LoadBalancer = WeightedRoundRobinLoadBalancer(),
    private val randomSource: () -> Double = { Random.nextDouble() },
) {
    fun route(
        request: RoutingRequest,
        requestId: RequestId,
        stickyCandidateKey: String? = null,
    ): RoutingDecision {
        val candidates =
            candidateFactory.build(request.capabilityId, request.modelAlias, request.tenantId, requestId)

        val policies = policyRepository.findEffective(request.tenantId, workflowId = null)
        val effectivePolicy =
            PolicyResolutionService.resolve(
                platform = policies.filter { it.scope == PolicyScope.PLATFORM }.flatMap { it.rules },
                tenant = policies.filter { it.scope == PolicyScope.TENANT }.flatMap { it.rules },
                workflow = policies.filter { it.scope == PolicyScope.WORKFLOW }.flatMap { it.rules },
                user = emptyList(),
            )

        val filtered =
            RoutingHardFilters.apply(
                candidates,
                regionRequirement = request.constraints.region,
                excludeProviders = request.constraints.excludeProviders,
                isDenied = { candidate -> effectivePolicy.isDenied(candidate, request.capabilityId) },
            )
        if (filtered.isEmpty()) throw NoCandidateAvailableException(request.capabilityId)

        val baseWeights = RoutingDomainService.resolveWeights(request.preferences.optimizeFor)
        val weights = effectivePolicy.override.weights?.let { toRoutingWeights(it, baseWeights) } ?: baseWeights
        val scored = routingStrategy.score(filtered, weights)
        val stickyApplied = RoutingDomainService.applyStickyBonus(scored, stickyCandidateKey)

        val chosen = loadBalancer.choose(stickyApplied, randomSource())
        val depth = effectivePolicy.override.fallbackDepth ?: RoutingDomainService.DEFAULT_FALLBACK_DEPTH
        val chain = RoutingDomainService.buildFallbackChain(stickyApplied, chosen.key, depth)

        return RoutingDecision(
            chain = chain,
            scoredCandidates = stickyApplied,
            appliedPolicyId = policies.firstOrNull()?.policyId,
            reason = "selected=${chosen.key}; candidateCount=${filtered.size}",
            costEstimationStubbed = candidateFactory.costEstimationStubbed,
        )
    }

    private fun toRoutingWeights(
        overrideWeights: Map<String, Double>,
        base: RoutingWeights,
    ): RoutingWeights =
        RoutingWeights(
            cost = overrideWeights["cost"] ?: base.cost,
            latency = overrideWeights["latency"] ?: base.latency,
            availability = overrideWeights["availability"] ?: base.availability,
            priority = overrideWeights["priority"] ?: base.priority,
        )
}
