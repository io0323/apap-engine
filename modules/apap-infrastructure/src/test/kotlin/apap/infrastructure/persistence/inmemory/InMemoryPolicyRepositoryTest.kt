package apap.infrastructure.persistence.inmemory

import apap.domain.model.routing.PolicyEffect
import apap.domain.model.routing.PolicyRule
import apap.domain.model.routing.PolicyRuleTarget
import apap.domain.model.routing.PolicyScope
import apap.domain.model.routing.RoutingPolicy
import apap.domain.model.vo.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InMemoryPolicyRepositoryTest {
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val rule = PolicyRule(PolicyEffect.ALLOW, PolicyRuleTarget())

    @Test
    fun `findEffective returns PLATFORM policies for any tenant, plus that tenant's own`() {
        val repo = InMemoryPolicyRepository()
        repo.save(RoutingPolicy(policyId = "platform", scope = PolicyScope.PLATFORM, rules = listOf(rule)))
        repo.save(
            RoutingPolicy(policyId = "mine", scope = PolicyScope.TENANT, tenantId = tenantId, rules = listOf(rule)),
        )
        repo.save(
            RoutingPolicy(
                policyId = "other",
                scope = PolicyScope.TENANT,
                tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA1"),
                rules = listOf(rule),
            ),
        )

        val effective = repo.findEffective(tenantId, null).map { it.policyId }.toSet()

        assertEquals(setOf("platform", "mine"), effective)
    }

    @Test
    fun `findEffective excludes ARCHIVED policies`() {
        val repo = InMemoryPolicyRepository()
        repo.save(
            RoutingPolicy(policyId = "archived", scope = PolicyScope.PLATFORM, rules = listOf(rule)).archive(),
        )

        assertEquals(emptyList<RoutingPolicy>(), repo.findEffective(tenantId, null))
    }
}
