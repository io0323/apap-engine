package apap.provider

import apap.domain.model.routing.PolicyEffect
import apap.domain.model.routing.PolicyRule
import apap.domain.model.routing.PolicyRuleTarget
import apap.domain.model.routing.PolicyScope
import apap.domain.model.routing.RoutingPolicy
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.TenantId
import apap.testkit.inmemory.InMemoryCapabilityRepository
import apap.testkit.inmemory.InMemoryPolicyRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 05_シーケンス設計.md 5.9: Capability DiscoveryはテナントPolicyで禁止されたCapabilityを除外する。 */
class CapabilityDiscoveryQueryTest {
    private val capabilityRepository = InMemoryCapabilityRepository()
    private val policyRepository = InMemoryPolicyRepository()
    private val query = CapabilityDiscoveryQuery(capabilityRepository, policyRepository)
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FAV")

    private fun seedCapabilities() {
        CapabilityRegistry(capabilityRepository).seedInitialCapabilities()
    }

    @Test
    fun `listCapabilities returns all capabilities when no deny policy applies`() {
        seedCapabilities()

        val result = query.listCapabilities(tenantId)

        assertTrue(result.any { it.capabilityId == CapabilityId("chat") })
    }

    @Test
    fun `listCapabilities excludes a capability denied by a tenant policy`() {
        seedCapabilities()
        policyRepository.save(
            RoutingPolicy(
                policyId = "tenant-deny-embedding",
                scope = PolicyScope.TENANT,
                tenantId = tenantId,
                rules =
                    listOf(
                        PolicyRule(
                            effect = PolicyEffect.DENY,
                            target = PolicyRuleTarget(capabilities = setOf(CapabilityId("embedding"))),
                        ),
                    ),
            ),
        )

        val result = query.listCapabilities(tenantId)

        assertFalse(result.any { it.capabilityId == CapabilityId("embedding") })
        assertTrue(result.any { it.capabilityId == CapabilityId("chat") })
    }
}
