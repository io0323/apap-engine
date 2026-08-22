package apap.domain.service.routing

import apap.domain.model.execution.CbState
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Score
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class RoutingTypesTest {
    private fun candidate(
        p50LatencyMs: Double = 50.0,
        p90LatencyMs: Double = 100.0,
        successRate: Double = 1.0,
        providerPriority: Int = 50,
        modelPriority: Int = 50,
    ) = Candidate(
        providerId = ProviderId(testUlid('A')),
        modelId = ModelId(testUlid('B')),
        providerStatus = ProviderStatus.ACTIVE,
        modelStatus = ModelStatus.ACTIVE,
        cbState = CbState.CLOSED,
        health = ProviderHealthStatus.UP,
        supportedRegions = emptySet(),
        estimatedCost = Money(BigDecimal.ONE, "USD"),
        p50LatencyMs = p50LatencyMs,
        p90LatencyMs = p90LatencyMs,
        successRate = successRate,
        providerPriority = providerPriority,
        modelPriority = modelPriority,
        hasPermission = true,
        quotaRemaining = true,
    )

    @Test
    fun `rejects a negative p50LatencyMs`() {
        assertThrows(IllegalArgumentException::class.java) { candidate(p50LatencyMs = -1.0) }
    }

    @Test
    fun `rejects a negative p90LatencyMs`() {
        assertThrows(IllegalArgumentException::class.java) { candidate(p90LatencyMs = -1.0) }
    }

    @Test
    fun `rejects a successRate outside 0 to 1`() {
        assertThrows(IllegalArgumentException::class.java) { candidate(successRate = 1.1) }
        assertThrows(IllegalArgumentException::class.java) { candidate(successRate = -0.1) }
    }

    @Test
    fun `rejects providerPriority or modelPriority outside 1 to 100`() {
        assertThrows(IllegalArgumentException::class.java) { candidate(providerPriority = 0) }
        assertThrows(IllegalArgumentException::class.java) { candidate(providerPriority = 101) }
        assertThrows(IllegalArgumentException::class.java) { candidate(modelPriority = 0) }
        assertThrows(IllegalArgumentException::class.java) { candidate(modelPriority = 101) }
    }

    @Test
    fun `RoutingWeights rejects a weight outside 0 to 1`() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutingWeights(cost = 1.5, latency = 0.0, availability = 0.0, priority = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoutingWeights(cost = -0.1, latency = 0.0, availability = 0.0, priority = 0.0)
        }
    }

    @Test
    fun `key combines providerId and modelId`() {
        val c = candidate()
        assert(c.key == "${c.providerId.value}:${c.modelId.value}")
    }

    @Test
    fun `ScoredCandidate pairs a candidate with a score`() {
        val scored = ScoredCandidate(candidate(), Score(0.5))
        assert(scored.score.value == 0.5)
    }

    @Test
    fun `FallbackChain rejects an empty candidate list`() {
        assertThrows(IllegalArgumentException::class.java) { FallbackChain(emptyList()) }
    }
}
