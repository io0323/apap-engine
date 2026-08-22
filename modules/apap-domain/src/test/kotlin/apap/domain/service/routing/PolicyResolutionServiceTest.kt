package apap.domain.service.routing

import apap.domain.model.execution.CbState
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.routing.PolicyEffect
import apap.domain.model.routing.PolicyOverride
import apap.domain.model.routing.PolicyRule
import apap.domain.model.routing.PolicyRuleTarget
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PolicyResolutionServiceTest {
    private val providerId = ProviderId(testUlid('A'))
    private val modelId = ModelId(testUlid('B'))
    private val capabilityId = CapabilityId("chat")

    private val jpEast = Region.of("jp-east", RegionCodeTable(setOf("jp-east", "us-west")))
    private val usWest = Region.of("us-west", RegionCodeTable(setOf("jp-east", "us-west")))

    private fun candidateFor(
        provider: ProviderId,
        model: ModelId,
        supportedRegions: Set<Region> = emptySet(),
    ) = Candidate(
        providerId = provider,
        modelId = model,
        providerStatus = ProviderStatus.ACTIVE,
        modelStatus = ModelStatus.ACTIVE,
        cbState = CbState.CLOSED,
        health = ProviderHealthStatus.UP,
        supportedRegions = supportedRegions,
        estimatedCost = Money(BigDecimal.ONE, "USD"),
        p50LatencyMs = 50.0,
        p90LatencyMs = 100.0,
        successRate = 1.0,
        providerPriority = 50,
        modelPriority = 50,
        hasPermission = true,
        quotaRemaining = true,
    )

    @Test
    fun `a lower tier ALLOW cannot remove an upper tier DENY`() {
        val platformDeny = PolicyRule(PolicyEffect.DENY, PolicyRuleTarget(providers = setOf(providerId)))
        val userAllow = PolicyRule(PolicyEffect.ALLOW, PolicyRuleTarget(providers = setOf(providerId)))

        val effective =
            PolicyResolutionService.resolve(
                platform = listOf(platformDeny),
                tenant = emptyList(),
                workflow = emptyList(),
                user = listOf(userAllow),
            )

        assertTrue(effective.isDenied(candidateFor(providerId, modelId), capabilityId))
    }

    @Test
    fun `deny rules accumulate across all four tiers`() {
        val tenantDeny = PolicyRule(PolicyEffect.DENY, PolicyRuleTarget(models = setOf(modelId)))
        val effective =
            PolicyResolutionService.resolve(
                platform = emptyList(),
                tenant = listOf(tenantDeny),
                workflow = emptyList(),
                user = emptyList(),
            )
        assertTrue(effective.isDenied(candidateFor(providerId, modelId), capabilityId))
    }

    @Test
    fun `a candidate matching no deny target is not denied`() {
        val deny = PolicyRule(PolicyEffect.DENY, PolicyRuleTarget(providers = setOf(ProviderId(testUlid('Z')))))
        val effective = PolicyResolutionService.resolve(listOf(deny), emptyList(), emptyList(), emptyList())
        assertFalse(effective.isDenied(candidateFor(providerId, modelId), capabilityId))
    }

    @Test
    fun `deny target can match on capabilityId`() {
        val deny = PolicyRule(PolicyEffect.DENY, PolicyRuleTarget(capabilities = setOf(capabilityId)))
        val effective = PolicyResolutionService.resolve(listOf(deny), emptyList(), emptyList(), emptyList())
        assertTrue(effective.isDenied(candidateFor(providerId, modelId), capabilityId))
        assertFalse(effective.isDenied(candidateFor(providerId, modelId), CapabilityId("embedding")))
    }

    @Test
    fun `deny target can match on region`() {
        val deny = PolicyRule(PolicyEffect.DENY, PolicyRuleTarget(regions = setOf(jpEast)))
        val effective = PolicyResolutionService.resolve(listOf(deny), emptyList(), emptyList(), emptyList())
        assertTrue(
            effective.isDenied(candidateFor(providerId, modelId, supportedRegions = setOf(jpEast)), capabilityId),
        )
        assertFalse(
            effective.isDenied(candidateFor(providerId, modelId, supportedRegions = setOf(usWest)), capabilityId),
        )
    }

    @Test
    fun `override is last-write-wins from PLATFORM to USER`() {
        val platformOverride = PolicyOverride(fallbackDepth = 3, retryMax = 3)
        val platform = PolicyRule(PolicyEffect.ALLOW, PolicyRuleTarget(), override = platformOverride)
        val user = PolicyRule(PolicyEffect.ALLOW, PolicyRuleTarget(), override = PolicyOverride(fallbackDepth = 1))

        val effective = PolicyResolutionService.resolve(listOf(platform), emptyList(), emptyList(), listOf(user))

        // userのfallbackDepthが後勝ちで上書きする
        assertEquals(1, effective.override.fallbackDepth)
        // userが指定しなかったretryMaxはplatformの値が維持される
        assertEquals(3, effective.override.retryMax)
    }

    @Test
    fun `rules without an override do not disturb overrides set by other rules in the same resolve`() {
        val noOverrideRule = PolicyRule(PolicyEffect.ALLOW, PolicyRuleTarget())
        val withOverrideRule =
            PolicyRule(PolicyEffect.ALLOW, PolicyRuleTarget(), override = PolicyOverride(retryMax = 5))

        val effective =
            PolicyResolutionService.resolve(
                platform = listOf(noOverrideRule, withOverrideRule),
                tenant = emptyList(),
                workflow = emptyList(),
                user = emptyList(),
            )

        assertEquals(5, effective.override.retryMax)
    }

    @Test
    fun `workflow override wins over tenant override for the same field`() {
        val tenantOverride = PolicyOverride(cacheTtlSeconds = 3600)
        val tenant = PolicyRule(PolicyEffect.ALLOW, PolicyRuleTarget(), override = tenantOverride)
        val workflowOverride = PolicyOverride(cacheTtlSeconds = 60)
        val workflow = PolicyRule(PolicyEffect.ALLOW, PolicyRuleTarget(), override = workflowOverride)

        val effective = PolicyResolutionService.resolve(emptyList(), listOf(tenant), listOf(workflow), emptyList())

        assertEquals(60, effective.override.cacheTtlSeconds)
    }
}
