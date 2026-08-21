package apap.domain.service.routing

import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.vo.OptimizeFor
import apap.domain.model.vo.Score

/**
 * 04_ドメイン設計.md 4.6 / 02_システム仕様.md 2.5.2: 候補抽出後の
 * 「ハードフィルタ(a〜g、順序固定) → スコアリング → Sticky補正(+0.05) → LB(スコア差0.02以内) →
 * Chain構築(既定3段)」を実装する。副作用なしの純粋関数として、各ステップを独立関数で提供する。
 * ハードフィルタ（a〜g）は[RoutingHardFilters]に分離。候補抽出そのもの
 * （Capability Resolverの責務）はこのServiceの対象外。
 */
object RoutingDomainService {
    const val DEFAULT_STICKY_BONUS: Double = 0.05
    const val DEFAULT_LOAD_BALANCE_THRESHOLD: Double = 0.02
    const val DEFAULT_FALLBACK_DEPTH: Int = 3

    private const val MIN_SCORE = 0.0
    private const val MAX_SCORE = 1.0
    private const val MAX_PRIORITY = 100.0
    private const val HEALTH_FACTOR_UP = 1.0
    private const val HEALTH_FACTOR_DEGRADED = 0.5
    private const val HEALTH_FACTOR_DOWN = 0.0
    private const val PRIORITY_COMPONENT_COUNT = 2.0

    // --- 2.5.2 step3: スコアリング ----------------------------------------------------------

    /** 2.5.2 重み既定値。Policyでの上書きは呼び出し側が独自の[RoutingWeights]を構築して行う。 */
    fun resolveWeights(optimizeFor: OptimizeFor): RoutingWeights =
        when (optimizeFor) {
            OptimizeFor.BALANCED -> RoutingWeights(cost = 0.25, latency = 0.25, availability = 0.30, priority = 0.20)
            OptimizeFor.COST -> RoutingWeights(cost = 0.55, latency = 0.10, availability = 0.25, priority = 0.10)
            OptimizeFor.LATENCY -> RoutingWeights(cost = 0.10, latency = 0.55, availability = 0.25, priority = 0.10)
            OptimizeFor.QUALITY -> RoutingWeights(cost = 0.05, latency = 0.10, availability = 0.25, priority = 0.60)
        }

    /**
     * score = w_cost×S_cost + w_latency×S_latency + w_avail×S_avail + w_priority×S_priority。
     * S_costとS_latencyは候補集合内でのmin-max正規化（全候補が同値の場合は差をつけない=1.0）。
     */
    fun computeScores(
        candidates: List<Candidate>,
        weights: RoutingWeights,
    ): List<ScoredCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val costs = candidates.map { it.estimatedCost.amount.toDouble() }
        val latencies = candidates.map { it.p90LatencyMs }
        return candidates.map { candidate ->
            val sCost = MAX_SCORE - normalize(costs, candidate.estimatedCost.amount.toDouble())
            val sLatency = MAX_SCORE - normalize(latencies, candidate.p90LatencyMs)
            val sAvail = candidate.successRate * healthFactor(candidate.health)
            val normalizedProviderPriority = candidate.providerPriority / MAX_PRIORITY
            val normalizedModelPriority = candidate.modelPriority / MAX_PRIORITY
            val sPriority = (normalizedProviderPriority + normalizedModelPriority) / PRIORITY_COMPONENT_COUNT
            val raw =
                weights.cost * sCost +
                    weights.latency * sLatency +
                    weights.availability * sAvail +
                    weights.priority * sPriority
            ScoredCandidate(candidate, Score(raw.coerceIn(MIN_SCORE, MAX_SCORE)))
        }
    }

    private fun healthFactor(health: ProviderHealthStatus): Double =
        when (health) {
            ProviderHealthStatus.UP -> HEALTH_FACTOR_UP
            ProviderHealthStatus.DEGRADED -> HEALTH_FACTOR_DEGRADED
            ProviderHealthStatus.DOWN -> HEALTH_FACTOR_DOWN
        }

    private fun normalize(
        values: List<Double>,
        value: Double,
    ): Double {
        val min = values.min()
        val max = values.max()
        return if (max <= min) MIN_SCORE else (value - min) / (max - min)
    }

    // --- 2.5.2 step4: Sticky補正 ------------------------------------------------------------

    /** conversation_idがあり直前Turnと同一候補が残存する場合、その候補スコアへ+0.05。 */
    fun applyStickyBonus(
        scored: List<ScoredCandidate>,
        stickyCandidateKey: String?,
        bonus: Double = DEFAULT_STICKY_BONUS,
    ): List<ScoredCandidate> {
        if (stickyCandidateKey == null) return scored
        return scored.map { sc ->
            if (sc.candidate.key == stickyCandidateKey) sc.copy(score = sc.score.coercedPlus(bonus)) else sc
        }
    }

    // --- 2.5.2 step5: Load Balancing --------------------------------------------------------

    /**
     * 最高スコアとの差がthreshold以内の候補群に対し、重み付き（スコア比例）で1つ選択する。
     * `randomValue`（[0.0, 1.0)）は呼び出し側が供給する（Domain層はMath.random()等を直接呼ばない）。
     */
    fun selectViaLoadBalancing(
        scored: List<ScoredCandidate>,
        randomValue: Double,
        threshold: Double = DEFAULT_LOAD_BALANCE_THRESHOLD,
    ): Candidate {
        require(scored.isNotEmpty()) { "scored must not be empty" }
        require(randomValue in MIN_SCORE..MAX_SCORE) { "randomValue must be within 0.0..1.0: $randomValue" }

        val maxScore = scored.maxOf { it.score.value }
        val topGroup = scored.filter { maxScore - it.score.value <= threshold }
        val totalWeight = topGroup.sumOf { it.score.value }

        return if (topGroup.size == 1 || totalWeight <= MIN_SCORE) {
            topGroup.first().candidate
        } else {
            pickByWeight(topGroup, randomValue * totalWeight)
        }
    }

    private fun pickByWeight(
        topGroup: List<ScoredCandidate>,
        target: Double,
    ): Candidate {
        var cumulative = MIN_SCORE
        for (scoredCandidate in topGroup) {
            cumulative += scoredCandidate.score.value
            if (target <= cumulative) return scoredCandidate.candidate
        }
        return topGroup.last().candidate
    }

    // --- 2.5.2 step6: Fallback Chain構築 ----------------------------------------------------

    /** LBで選ばれた候補を先頭に、残りはスコア降順で並べ、既定3段まで採用する。 */
    fun buildFallbackChain(
        scored: List<ScoredCandidate>,
        headCandidateKey: String,
        depth: Int = DEFAULT_FALLBACK_DEPTH,
    ): FallbackChain {
        require(depth >= 1) { "depth must be >= 1: $depth" }
        val descending = scored.sortedByDescending { it.score.value }
        val head = descending.first { it.candidate.key == headCandidateKey }
        val rest = descending.filterNot { it.candidate.key == headCandidateKey }
        val ordered = (listOf(head) + rest).map { it.candidate }
        return FallbackChain(ordered.take(depth))
    }
}
