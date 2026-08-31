package apap.infrastructure.jdbc

import apap.domain.event.EventMetadata
import apap.domain.event.PolicyUpdated
import apap.domain.model.reconstruct
import apap.domain.model.routing.PolicyEffect
import apap.domain.model.routing.PolicyRule
import apap.domain.model.routing.PolicyRuleTarget
import apap.domain.model.routing.PolicyScope
import apap.domain.model.routing.PolicyStatus
import apap.domain.model.routing.RoutingPolicy
import apap.domain.model.routing.applyRoutingPolicyEvent
import apap.domain.model.vo.TenantId
import apap.testkit.inmemory.InMemoryClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.sql.DataSource

/**
 * ローカルPostgreSQL（`docker compose -f tools/docker-compose.yaml up -d rdbms`）に対する統合テスト。
 * ADR-0026: スナップショット＋差分イベント再生による再構築を、全イベント再生と比較して検証する。
 */
class JdbcPolicyRepositoryTest {
    private lateinit var dataSource: DataSource
    private val policyId = "01ARZ3NDEKTSV4RRFFQ69G5FA6"
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val rule = PolicyRule(PolicyEffect.ALLOW, PolicyRuleTarget())

    @BeforeEach
    fun setUp() {
        dataSource = JdbcTestSupport.freshDataSource()
    }

    private fun repo(snapshotEveryNEvents: Int = 100) =
        JdbcPolicyRepository(
            dataSource,
            JdbcEventStoreRepository(dataSource, InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))),
            snapshotEveryNEvents = snapshotEveryNEvents,
        )

    private fun meta(version: Long) =
        EventMetadata("evt-$version", Instant.parse("2026-01-01T00:00:00Z"), "trace-1", tenantId, policyId, version)

    private fun updated(
        version: Int,
        rules: List<PolicyRule>,
    ) = PolicyUpdated(meta(version.toLong()), policyId, "TENANT", tenantId, null, rules, version, PolicyStatus.ACTIVE)

    @Test
    fun `reconstructing via loadSnapshot plus the events since it matches reconstructing from all events`() {
        val newRule = PolicyRule(PolicyEffect.DENY, PolicyRuleTarget())
        val events = listOf(updated(1, listOf(rule)), updated(2, listOf(rule, newRule)))

        val snapshotWriter = repo(snapshotEveryNEvents = 1)
        events.forEach { snapshotWriter.saveEvents(policyId, listOf(it)) }

        val eventStore = JdbcEventStoreRepository(dataSource, InMemoryClock(Instant.parse("2026-01-01T00:00:00Z")))
        val snapshot = eventStore.loadSnapshot(policyId, RoutingPolicy::class)
        assertTrue(snapshot != null, "expected a snapshot to have been taken (snapshotEveryNEvents=1)")

        val fromSnapshotPath = repo(snapshotEveryNEvents = 1).findById(policyId)
        val fullReplay = reconstruct(events, null, ::applyRoutingPolicyEvent)

        assertEquals(fullReplay, fromSnapshotPath)
    }

    @Test
    fun `findEffective resolves PLATFORM scope policies, reconstructed via their own event stream`() {
        val repo = repo()
        val platformPolicy = RoutingPolicy(policyId, PolicyScope.PLATFORM, null, null, listOf(rule), 1)
        repo.save(platformPolicy)
        repo.saveEvents(
            policyId,
            listOf(PolicyUpdated(meta(1), policyId, "PLATFORM", null, null, listOf(rule), 1, PolicyStatus.ACTIVE)),
        )

        val found = repo.findEffective(tenantId, null)

        assertEquals(listOf(policyId), found.map { it.policyId })
    }
}
