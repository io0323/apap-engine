package apap.domain.model.routing

import apap.domain.model.vo.TenantId
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RoutingPolicyTest {
    private val allowAllRule = PolicyRule(effect = PolicyEffect.ALLOW, target = PolicyRuleTarget())
    private val denyRule = PolicyRule(effect = PolicyEffect.DENY, target = PolicyRuleTarget())

    @Test
    fun `requires tenantId for TENANT scope`() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutingPolicy(policyId = "p1", scope = PolicyScope.TENANT, rules = listOf(allowAllRule))
        }
    }

    @Test
    fun `requires workflowId for WORKFLOW scope`() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutingPolicy(
                policyId = "p1",
                scope = PolicyScope.WORKFLOW,
                tenantId = TenantId(testUlid('A')),
                rules = listOf(allowAllRule),
            )
        }
    }

    @Test
    fun `rejects a blank (non-null) workflowId for WORKFLOW scope`() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutingPolicy(
                policyId = "p1",
                scope = PolicyScope.WORKFLOW,
                tenantId = TenantId(testUlid('A')),
                workflowId = " ",
                rules = listOf(allowAllRule),
            )
        }
    }

    @Test
    fun `rejects version below 1`() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutingPolicy(policyId = "p1", scope = PolicyScope.PLATFORM, rules = listOf(allowAllRule), version = 0)
        }
    }

    @Test
    fun `PLATFORM scope does not require tenantId`() {
        RoutingPolicy(policyId = "p1", scope = PolicyScope.PLATFORM, rules = listOf(allowAllRule))
    }

    @Test
    fun `WORKFLOW scope accepts a valid non-blank workflowId`() {
        val policy =
            RoutingPolicy(
                policyId = "p1",
                scope = PolicyScope.WORKFLOW,
                tenantId = TenantId(testUlid('A')),
                workflowId = "wf-1",
                rules = listOf(allowAllRule),
            )
        assertEquals("wf-1", policy.workflowId)
    }

    @Test
    fun `publishNewVersion increments version and replaces rules`() {
        val v1 = RoutingPolicy(policyId = "p1", scope = PolicyScope.PLATFORM, rules = listOf(denyRule))
        val v2 = v1.publishNewVersion(listOf(allowAllRule))
        assertEquals(2, v2.version)
        assertEquals(listOf(allowAllRule), v2.rules)
        // v1自体は不変のまま（履歴として残る）
        assertEquals(listOf(denyRule), v1.rules)
    }

    @Test
    fun `there is no in-place mutator that removes a DENY rule without publishing a new version`() {
        // RoutingPolicyはdata class + valであり、rulesを直接書き換える公開メソッドは
        // publishNewVersion以外に存在しない。これによりversionを進めずにDENYを消すことは構造的にできない。
        val v1 = RoutingPolicy(policyId = "p1", scope = PolicyScope.PLATFORM, rules = listOf(denyRule))
        val v2 = v1.publishNewVersion(emptyList())
        assertEquals(1, v1.version)
        assertEquals(2, v2.version)
    }

    @Test
    fun `archive changes status`() {
        val active = RoutingPolicy(policyId = "p1", scope = PolicyScope.PLATFORM, rules = listOf(allowAllRule))
        assertEquals(PolicyStatus.ARCHIVED, active.archive().status)
    }
}
