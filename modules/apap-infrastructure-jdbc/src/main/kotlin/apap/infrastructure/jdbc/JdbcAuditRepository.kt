package apap.infrastructure.jdbc

import apap.domain.model.audit.AuditRecord
import apap.domain.model.audit.AuditSearchCriteria
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.Cost
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.Usage
import apap.domain.port.AuditRepository
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * [AuditRepository]のJDBC実装（ADR-0025: PostgreSQL、素のJDBC）。追記専用
 * （UPDATE/DELETEは提供しない、12章ER図の設計注記どおりDBロール権限もアプリ側から
 * 剥奪する運用を想定——権限管理自体は本クラスの範囲外）。
 */
class JdbcAuditRepository(
    private val dataSource: DataSource,
    private val objectMapper: ObjectMapper = JdbcSupport.objectMapper,
) : AuditRepository {
    @Suppress("MagicNumber") // JDBC positional parameter indices, not meaningful as named constants.
    override fun append(record: AuditRecord) {
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO audit_record (
                        audit_id, request_id, trace_id, tenant_id, principal, capability_id, model_alias,
                        provider_id, model_id, routing_decision, request_digest, response_digest, request_body,
                        status, error_code, usage, cost, duration_ms, retries, fallbacks, conversation_id, occurred_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, record.auditId)
                    stmt.setString(2, record.requestId.value)
                    stmt.setString(3, record.traceId)
                    stmt.setString(4, record.tenantId.value)
                    stmt.setString(5, record.principal)
                    stmt.setString(6, record.capabilityId)
                    stmt.setString(7, record.modelAlias)
                    stmt.setString(8, record.providerId?.value)
                    stmt.setString(9, record.modelId?.value)
                    stmt.setString(10, objectMapper.writeValueAsString(record.routingDecision))
                    stmt.setString(11, record.requestDigest)
                    stmt.setString(12, record.responseDigest)
                    stmt.setString(13, record.requestBody)
                    stmt.setString(14, record.status)
                    stmt.setString(15, record.errorCode?.name)
                    stmt.setString(16, objectMapper.writeValueAsString(record.usage))
                    stmt.setString(17, objectMapper.writeValueAsString(record.cost))
                    stmt.setLong(18, record.durationMs)
                    stmt.setInt(19, record.retries)
                    stmt.setInt(20, record.fallbacks)
                    stmt.setString(21, record.conversationId?.value)
                    stmt.setTimestamp(22, Timestamp.from(record.occurredAt))
                    stmt.executeUpdate()
                }
        }
    }

    @Suppress("NestedBlockDepth") // connection/statement/resultset/while is inherent to raw JDBC.
    override fun search(criteria: AuditSearchCriteria): List<AuditRecord> {
        val (whereClause, params) = buildWhere(criteria)
        val sql = "SELECT * FROM audit_record $whereClause ORDER BY occurred_at DESC"
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { index, param -> stmt.setObject(index + 1, param) }
                stmt.executeQuery().use { rs ->
                    val results = mutableListOf<AuditRecord>()
                    while (rs.next()) results += toRecord(rs)
                    return results
                }
            }
        }
    }

    private fun buildWhere(criteria: AuditSearchCriteria): Pair<String, List<Any>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        criteria.fromInclusive?.let {
            conditions += "occurred_at >= ?"
            params += Timestamp.from(it)
        }
        criteria.toExclusive?.let {
            conditions += "occurred_at < ?"
            params += Timestamp.from(it)
        }
        criteria.tenantId?.let {
            conditions += "tenant_id = ?"
            params += it.value
        }
        criteria.providerId?.let {
            conditions += "provider_id = ?"
            params += it.value
        }
        criteria.errorCode?.let {
            conditions += "error_code = ?"
            params += it.name
        }
        criteria.requestId?.let {
            conditions += "request_id = ?"
            params += it.value
        }
        if (conditions.isEmpty()) return "" to emptyList()
        return "WHERE " + conditions.joinToString(" AND ") to params
    }

    private fun toRecord(rs: ResultSet): AuditRecord =
        AuditRecord(
            auditId = rs.getString("audit_id"),
            requestId = RequestId(rs.getString("request_id")),
            traceId = rs.getString("trace_id"),
            tenantId = TenantId(rs.getString("tenant_id")),
            principal = rs.getString("principal"),
            capabilityId = rs.getString("capability_id"),
            modelAlias = rs.getString("model_alias"),
            providerId = rs.getString("provider_id")?.let { ProviderId(it) },
            modelId = rs.getString("model_id")?.let { ModelId(it) },
            // routing_decisionはJSONB列だが、RoutingDecision.toAuditSummary()の実際の値は
            // 自由形式のテキスト（JSON構造ではない）。JSON文字列スカラーとして保存しているため、
            // 読み戻しはtoString()（JSON表現、引用符が残る）ではなくasText()（元のテキスト）を使う。
            routingDecision = objectMapper.readTree(rs.getString("routing_decision")).asText(),
            requestDigest = rs.getString("request_digest"),
            responseDigest = rs.getString("response_digest"),
            requestBody = rs.getString("request_body"),
            status = rs.getString("status"),
            errorCode = rs.getString("error_code")?.let { ErrorCode.valueOf(it) },
            usage = objectMapper.readValue(rs.getString("usage"), Usage::class.java),
            cost = objectMapper.readValue(rs.getString("cost"), Cost::class.java),
            durationMs = rs.getLong("duration_ms"),
            retries = rs.getInt("retries"),
            fallbacks = rs.getInt("fallbacks"),
            conversationId = rs.getString("conversation_id")?.let { ConversationId(it) },
            occurredAt = rs.getTimestamp("occurred_at").toInstant(),
        )
}
