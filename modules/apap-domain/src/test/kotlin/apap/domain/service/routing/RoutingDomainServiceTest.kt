package apap.domain.service.routing

import apap.domain.model.execution.CbState
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.OptimizeFor
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.Score
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class RoutingDomainServiceTest {
    private val jpEast = Region.of("jp-east", RegionCodeTable(setOf("jp-east", "us-west")))
    private val usWest = Region.of("us-west", RegionCodeTable(setOf("jp-east", "us-west")))

    // テスト用フィクスチャビルダーとして意図的に全フィールドを既定値付きで公開しているため、
    // パラメータ数の多さはLongParameterListの対象外とする。
    @Suppress("LongParameterList")
    private fun candidate(
        provider: Char = 'A',
        model: Char = 'B',
        providerStatus: ProviderStatus = ProviderStatus.ACTIVE,
        modelStatus: ModelStatus = ModelStatus.ACTIVE,
        isCanaryEligible: Boolean = false,
        cbState: CbState = CbState.CLOSED,
        health: ProviderHealthStatus = ProviderHealthStatus.UP,
        supportedRegions: Set<Region> = setOf(jpEast),
        estimatedCost: Money = Money(BigDecimal("1.00"), "USD"),
        p50LatencyMs: Double = 50.0,
        p90LatencyMs: Double = 100.0,
        successRate: Double = 1.0,
        providerPriority: Int = 50,
        modelPriority: Int = 50,
        hasPermission: Boolean = true,
        quotaRemaining: Boolean = true,
    ) = Candidate(
        providerId = ProviderId(testUlid(provider)),
        modelId = ModelId(testUlid(model)),
        providerStatus = providerStatus,
        modelStatus = modelStatus,
        isCanaryEligible = isCanaryEligible,
        cbState = cbState,
        health = health,
        supportedRegions = supportedRegions,
        estimatedCost = estimatedCost,
        p50LatencyMs = p50LatencyMs,
        p90LatencyMs = p90LatencyMs,
        successRate = successRate,
        providerPriority = providerPriority,
        modelPriority = modelPriority,
        hasPermission = hasPermission,
        quotaRemaining = quotaRemaining,
    )

    // --- ハードフィルタ: 各条件が単独で候補を除外すること -----------------------------------

    @Test
    fun `a - excludes candidates whose provider or model is not ACTIVE`() {
        val ok = candidate()
        val badProvider = candidate('C', 'D', providerStatus = ProviderStatus.SUSPENDED)
        val badModel = candidate('E', 'F', modelStatus = ModelStatus.DEPRECATED)
        val result = RoutingHardFilters.apply(listOf(ok, badProvider, badModel), regionRequirement = null)
        assertEquals(listOf(ok), result)
    }

    @Test
    fun `a - TESTING model passes only when canary eligible`() {
        val eligible = candidate('A', 'B', modelStatus = ModelStatus.TESTING, isCanaryEligible = true)
        val notEligible = candidate('C', 'D', modelStatus = ModelStatus.TESTING, isCanaryEligible = false)
        val result = RoutingHardFilters.apply(listOf(eligible, notEligible), regionRequirement = null)
        assertEquals(listOf(eligible), result)
    }

    @Test
    fun `b - excludes candidates whose circuit breaker is OPEN`() {
        val ok = candidate()
        val open = candidate('C', 'D', cbState = CbState.OPEN)
        assertEquals(listOf(ok), RoutingHardFilters.apply(listOf(ok, open), regionRequirement = null))
    }

    @Test
    fun `c - excludes candidates whose health is DOWN`() {
        val ok = candidate()
        val down = candidate('C', 'D', health = ProviderHealthStatus.DOWN)
        assertEquals(listOf(ok), RoutingHardFilters.apply(listOf(ok, down), regionRequirement = null))
    }

    @Test
    fun `d - excludes candidates not supporting the requested region`() {
        val ok = candidate(supportedRegions = setOf(jpEast))
        val other = candidate('C', 'D', supportedRegions = setOf(usWest))
        val result = RoutingHardFilters.apply(listOf(ok, other), regionRequirement = jpEast)
        assertEquals(listOf(ok), result)
    }

    @Test
    fun `excludeProviders constraint removes matching candidates`() {
        val ok = candidate('A', 'B')
        val excluded = candidate('C', 'D')
        val result =
            RoutingHardFilters.apply(
                listOf(ok, excluded),
                regionRequirement = null,
                excludeProviders = setOf(excluded.providerId),
            )
        assertEquals(listOf(ok), result)
    }

    @Test
    fun `e - excludes candidates denied by policy`() {
        val ok = candidate('A', 'B')
        val denied = candidate('C', 'D')
        val result =
            RoutingHardFilters.apply(
                listOf(ok, denied),
                regionRequirement = null,
                isDenied = { it.providerId == denied.providerId },
            )
        assertEquals(listOf(ok), result)
    }

    @Test
    fun `f - excludes candidates without permission`() {
        val ok = candidate()
        val noPermission = candidate('C', 'D', hasPermission = false)
        assertEquals(listOf(ok), RoutingHardFilters.apply(listOf(ok, noPermission), regionRequirement = null))
    }

    @Test
    fun `g - excludes candidates with no quota remaining`() {
        val ok = candidate()
        val noQuota = candidate('C', 'D', quotaRemaining = false)
        assertEquals(listOf(ok), RoutingHardFilters.apply(listOf(ok, noQuota), regionRequirement = null))
    }

    // --- スコアリング -----------------------------------------------------------------------

    @Test
    fun `resolveWeights matches the 2_5_2 default table`() {
        assertEquals(RoutingWeights(0.25, 0.25, 0.30, 0.20), RoutingDomainService.resolveWeights(OptimizeFor.BALANCED))
        assertEquals(RoutingWeights(0.55, 0.10, 0.25, 0.10), RoutingDomainService.resolveWeights(OptimizeFor.COST))
        assertEquals(RoutingWeights(0.10, 0.55, 0.25, 0.10), RoutingDomainService.resolveWeights(OptimizeFor.LATENCY))
        assertEquals(RoutingWeights(0.05, 0.10, 0.25, 0.60), RoutingDomainService.resolveWeights(OptimizeFor.QUALITY))
    }

    @Test
    fun `cost optimization ranks the cheaper candidate first`() {
        val cheap = candidate('A', 'B', estimatedCost = Money(BigDecimal("0.10"), "USD"), p90LatencyMs = 500.0)
        val expensive = candidate('C', 'D', estimatedCost = Money(BigDecimal("10.00"), "USD"), p90LatencyMs = 500.0)
        val costWeights = RoutingDomainService.resolveWeights(OptimizeFor.COST)
        val scored = RoutingDomainService.computeScores(listOf(cheap, expensive), costWeights)
        val ranked = scored.sortedByDescending { it.score.value }
        assertEquals(cheap.key, ranked.first().candidate.key)
    }

    @Test
    fun `latency optimization ranks the faster candidate first`() {
        val fast = candidate('A', 'B', p90LatencyMs = 50.0, estimatedCost = Money(BigDecimal("5.00"), "USD"))
        val slow = candidate('C', 'D', p90LatencyMs = 900.0, estimatedCost = Money(BigDecimal("5.00"), "USD"))
        val latencyWeights = RoutingDomainService.resolveWeights(OptimizeFor.LATENCY)
        val scored = RoutingDomainService.computeScores(listOf(fast, slow), latencyWeights)
        val ranked = scored.sortedByDescending { it.score.value }
        assertEquals(fast.key, ranked.first().candidate.key)
    }

    @Test
    fun `DEGRADED health halves the availability score component`() {
        val up = candidate('A', 'B', health = ProviderHealthStatus.UP, successRate = 1.0)
        val degraded = candidate('C', 'D', health = ProviderHealthStatus.DEGRADED, successRate = 1.0)
        val weights = RoutingWeights(cost = 0.0, latency = 0.0, availability = 1.0, priority = 0.0)
        val scored = RoutingDomainService.computeScores(listOf(up, degraded), weights)
        val byKey = scored.associateBy { it.candidate.key }
        assertEquals(1.0, byKey.getValue(up.key).score.value, 1e-9)
        assertEquals(0.5, byKey.getValue(degraded.key).score.value, 1e-9)
    }

    @Test
    fun `computeScores returns an empty list for an empty candidate list`() {
        val weights = RoutingDomainService.resolveWeights(OptimizeFor.BALANCED)
        assertEquals(emptyList<ScoredCandidate>(), RoutingDomainService.computeScores(emptyList(), weights))
    }

    @Test
    fun `DOWN health zeroes out the availability score component`() {
        // 通常はRoutingHardFilters.passesHealthFilterでDOWNは事前に除外されるが、
        // computeScores自体が持つ分岐（healthFactor）を独立して検証する。
        val down = candidate('A', 'B', health = ProviderHealthStatus.DOWN, successRate = 1.0)
        val weights = RoutingWeights(cost = 0.0, latency = 0.0, availability = 1.0, priority = 0.0)
        val scored = RoutingDomainService.computeScores(listOf(down), weights)
        assertEquals(0.0, scored.single().score.value, 1e-9)
    }

    // --- Sticky補正 -------------------------------------------------------------------------

    @Test
    fun `sticky bonus increases the score of the matching candidate only`() {
        val a = candidate('A', 'B')
        val b = candidate('C', 'D')
        val weights = RoutingWeights(cost = 0.0, latency = 0.0, availability = 0.0, priority = 1.0)
        val scored = RoutingDomainService.computeScores(listOf(a, b), weights)
        val boosted = RoutingDomainService.applyStickyBonus(scored, stickyCandidateKey = a.key)
        val byKey = boosted.associateBy { it.candidate.key }
        val plain = scored.associateBy { it.candidate.key }
        assertEquals(plain.getValue(a.key).score.value + 0.05, byKey.getValue(a.key).score.value, 1e-9)
        assertEquals(plain.getValue(b.key).score.value, byKey.getValue(b.key).score.value, 1e-9)
    }

    @Test
    fun `sticky bonus is a no-op when stickyCandidateKey is null`() {
        val balancedWeights = RoutingDomainService.resolveWeights(OptimizeFor.BALANCED)
        val scored = RoutingDomainService.computeScores(listOf(candidate()), balancedWeights)
        assertEquals(scored, RoutingDomainService.applyStickyBonus(scored, stickyCandidateKey = null))
    }

    // --- Load Balancing ----------------------------------------------------------------------

    @Test
    fun `load balancing picks only within the near-max score group`() {
        val top1 =
            ScoredCandidate(
                candidate('A', 'B'),
                Score(0.90),
            )
        val top2 =
            ScoredCandidate(
                candidate('C', 'D'),
                Score(0.89),
            )
        val farBehind =
            ScoredCandidate(
                candidate('E', 'F'),
                Score(0.50),
            )
        val scored = listOf(top1, top2, farBehind)

        // randomValue=0.0 always selects the first candidate in the eligible (near-max) group
        val picked = RoutingDomainService.selectViaLoadBalancing(scored, randomValue = 0.0)
        assertTrue(picked.key == top1.candidate.key || picked.key == top2.candidate.key)
        assertFalse(picked.key == farBehind.candidate.key)
    }

    @Test
    fun `load balancing short-circuits to the only candidate when nothing else is within threshold`() {
        val onlyEligible = ScoredCandidate(candidate('A', 'B'), Score(0.90))
        val farBehind = ScoredCandidate(candidate('C', 'D'), Score(0.10))
        val picked = RoutingDomainService.selectViaLoadBalancing(listOf(onlyEligible, farBehind), randomValue = 0.5)
        assertEquals(onlyEligible.candidate.key, picked.key)
    }

    @Test
    fun `load balancing is deterministic for a fixed randomValue`() {
        val a =
            ScoredCandidate(
                candidate('A', 'B'),
                Score(0.80),
            )
        val b =
            ScoredCandidate(
                candidate('C', 'D'),
                Score(0.79),
            )
        val first = RoutingDomainService.selectViaLoadBalancing(listOf(a, b), randomValue = 0.3)
        val second = RoutingDomainService.selectViaLoadBalancing(listOf(a, b), randomValue = 0.3)
        assertEquals(first, second)
    }

    @Test
    fun `load balancing can pick a later candidate in the weighted group when randomValue is high enough`() {
        val a = ScoredCandidate(candidate('A', 'B'), Score(0.90))
        val b = ScoredCandidate(candidate('C', 'D'), Score(0.89))
        val picked = RoutingDomainService.selectViaLoadBalancing(listOf(a, b), randomValue = 0.99)
        assertEquals(b.candidate.key, picked.key)
    }

    @Test
    fun `load balancing rejects an empty scored list or an out of range randomValue`() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutingDomainService.selectViaLoadBalancing(emptyList(), randomValue = 0.0)
        }
        val scored = listOf(ScoredCandidate(candidate(), Score(0.5)))
        assertThrows(IllegalArgumentException::class.java) {
            RoutingDomainService.selectViaLoadBalancing(scored, randomValue = -0.01)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoutingDomainService.selectViaLoadBalancing(scored, randomValue = 1.01)
        }
    }

    @Test
    fun `load balancing short-circuits when the near-max group's total weight is zero`() {
        val a = ScoredCandidate(candidate('A', 'B'), Score(0.0))
        val b = ScoredCandidate(candidate('C', 'D'), Score(0.0))
        val picked = RoutingDomainService.selectViaLoadBalancing(listOf(a, b), randomValue = 0.5)
        assertEquals(a.candidate.key, picked.key)
    }

    // --- Chain構築 --------------------------------------------------------------------------

    @Test
    fun `fallback chain defaults to depth 3 in descending score order with the LB pick first`() {
        val scored =
            listOf(
                ScoredCandidate(
                    candidate('A', 'B'),
                    Score(0.95),
                ),
                ScoredCandidate(
                    candidate('C', 'D'),
                    Score(0.90),
                ),
                ScoredCandidate(
                    candidate('E', 'F'),
                    Score(0.80),
                ),
                ScoredCandidate(
                    candidate('G', 'H'),
                    Score(0.10),
                ),
            )
        // LBが2位候補を選出したケース: それでもChainの先頭はLB選出結果になる
        val headKey = scored[1].candidate.key
        val chain = RoutingDomainService.buildFallbackChain(scored, headCandidateKey = headKey)
        assertEquals(3, chain.candidates.size)
        assertEquals(headKey, chain.candidates.first().key)
        assertEquals(scored[0].candidate.key, chain.candidates[1].key)
        assertEquals(scored[2].candidate.key, chain.candidates[2].key)
    }

    @Test
    fun `fallback chain honors a custom depth`() {
        val scored =
            (1..5).map {
                ScoredCandidate(
                    candidate('A' + it, 'B'),
                    Score(1.0 / it),
                )
            }
        val chain =
            RoutingDomainService.buildFallbackChain(scored, headCandidateKey = scored.first().candidate.key, depth = 2)
        assertEquals(2, chain.candidates.size)
    }

    @Test
    fun `fallback chain rejects a depth below 1`() {
        val scored = listOf(ScoredCandidate(candidate(), Score(0.5)))
        assertThrows(IllegalArgumentException::class.java) {
            RoutingDomainService.buildFallbackChain(scored, headCandidateKey = scored.first().candidate.key, depth = 0)
        }
    }
}
