package apap.infrastructure.jdbc

import apap.domain.event.DomainEvent
import apap.domain.model.routing.RoutingPolicy
import apap.domain.model.routing.applyRoutingPolicyEvent
import apap.domain.model.vo.TenantId
import apap.domain.port.EventStoreRepository
import apap.domain.port.PolicyRepository
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Connection
import javax.sql.DataSource

/**
 * [PolicyRepository]のJDBC実装（ADR-0025、ADR-0026）。`findById`/`findEffective`は
 * [EventStoreRepository]経由の再構築を真の情報源とし、`routing_policy`テーブルは横断検索
 * （`findEffective`のWHERE句）のためのRead Model射影として`save`が同期的に書き込む
 * （[JdbcProviderRepository]と同じ設計判断）。
 */
class JdbcPolicyRepository(
    private val dataSource: DataSource,
    private val eventStoreRepository: EventStoreRepository,
    private val objectMapper: ObjectMapper = JdbcSupport.objectMapper,
    snapshotEveryNEvents: Int = 100,
) : PolicyRepository {
    private val support =
        EventSourcedRepositorySupport(
            eventStoreRepository,
            RoutingPolicy::class,
            ::applyRoutingPolicyEvent,
            snapshotEveryNEvents,
        )

    override fun findById(policyId: String): RoutingPolicy? = support.reconstruct(policyId)

    /** 02_システム仕様.md 2.5.3のスコープ解決を[InMemoryPolicyRepository]と同じ条件で再現する。 */
    @Suppress("NestedBlockDepth", "MagicNumber")
    override fun findEffective(
        tenantId: TenantId?,
        workflowId: String?,
    ): List<RoutingPolicy> {
        val sql =
            if (tenantId == null) {
                "SELECT policy_id FROM routing_policy WHERE status = 'ACTIVE' AND scope = 'PLATFORM'"
            } else {
                """
                SELECT policy_id FROM routing_policy WHERE status = 'ACTIVE' AND (
                    scope = 'PLATFORM' OR
                    (scope IN ('TENANT', 'USER') AND tenant_id = ?) OR
                    (scope = 'WORKFLOW' AND tenant_id = ? AND workflow_id = ?)
                )
                """.trimIndent()
            }
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                if (tenantId != null) {
                    stmt.setString(1, tenantId.value)
                    stmt.setString(2, tenantId.value)
                    stmt.setString(3, workflowId)
                }
                stmt.executeQuery().use { rs ->
                    val ids = mutableListOf<String>()
                    while (rs.next()) ids += rs.getString("policy_id")
                    return ids.mapNotNull { support.reconstruct(it) }
                }
            }
        }
    }

    override fun save(policy: RoutingPolicy) {
        dataSource.connection.use { conn -> upsertPolicy(conn, policy) }
    }

    @Suppress("MagicNumber")
    private fun upsertPolicy(
        conn: Connection,
        policy: RoutingPolicy,
    ) {
        conn
            .prepareStatement(
                """
                INSERT INTO routing_policy (policy_id, scope, tenant_id, workflow_id, rules, version, status)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (policy_id) DO UPDATE SET
                    scope = EXCLUDED.scope, tenant_id = EXCLUDED.tenant_id, workflow_id = EXCLUDED.workflow_id,
                    rules = EXCLUDED.rules, version = EXCLUDED.version, status = EXCLUDED.status
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, policy.policyId)
                stmt.setString(2, policy.scope.name)
                stmt.setString(3, policy.tenantId?.value)
                stmt.setString(4, policy.workflowId)
                stmt.setString(5, objectMapper.writeValueAsString(policy.rules))
                stmt.setInt(6, policy.version)
                stmt.setString(7, policy.status.name)
                stmt.executeUpdate()
            }
    }

    override fun saveEvents(
        policyId: String,
        events: List<DomainEvent>,
    ) {
        support.saveEvents(policyId, events)
    }
}
