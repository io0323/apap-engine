package apap.domain.model.routing

import apap.domain.event.BatchJobCancelled
import apap.domain.event.EventMetadata
import apap.domain.event.PolicyUpdated
import apap.domain.model.UnexpectedEventForAggregateException
import apap.domain.model.reconstruct
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

/** ADR-0026: イベント列からのRoutingPolicy再構築が、コマンド適用後の状態と一致することを検証する。 */
class RoutingPolicyReconstructionTest {
    private val policyId = testUlid('F')
    private val tenantId = TenantId(testUlid('A'))
    private val rule = PolicyRule(PolicyEffect.ALLOW, PolicyRuleTarget())

    private fun meta(version: Long) =
        EventMetadata(
            eventId = "evt-$version",
            occurredAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(version),
            traceId = "trace-1",
            tenantId = tenantId,
            aggregateId = policyId,
            version = version,
        )

    @Test
    fun `reconstruction from the full event list matches the state publishNewVersion would reach`() {
        val published =
            PolicyUpdated(meta(1), policyId, "TENANT", tenantId, null, listOf(rule), 1, PolicyStatus.ACTIVE)
        val newRule = PolicyRule(PolicyEffect.DENY, PolicyRuleTarget())
        val republished =
            PolicyUpdated(
                meta(2),
                policyId,
                "TENANT",
                tenantId,
                null,
                listOf(rule, newRule),
                2,
                PolicyStatus.ACTIVE,
            )

        val result = reconstruct(listOf(published, republished), null, ::applyRoutingPolicyEvent)

        // コマンド側: RoutingPolicy(...).publishNewVersion(listOf(rule, newRule))が到達する状態と一致する。
        val expected =
            RoutingPolicy(policyId, PolicyScope.TENANT, tenantId, null, listOf(rule), 1, PolicyStatus.ACTIVE)
                .publishNewVersion(listOf(rule, newRule))
        assertEquals(expected, result)
    }

    @Test
    fun `archive is fully captured by a single PolicyUpdated event`() {
        val archived =
            PolicyUpdated(meta(1), policyId, "PLATFORM", null, null, listOf(rule), 3, PolicyStatus.ARCHIVED)

        val result = reconstruct(listOf(archived), null, ::applyRoutingPolicyEvent)

        assertEquals(PolicyStatus.ARCHIVED, result?.status)
        assertEquals(PolicyScope.PLATFORM, result?.scope)
    }

    @Test
    fun `an event that does not belong to RoutingPolicy throws instead of silently ignoring it`() {
        assertThrows(UnexpectedEventForAggregateException::class.java) {
            applyRoutingPolicyEvent(null, BatchJobCancelled(meta(9), "job-1"))
        }
    }
}
