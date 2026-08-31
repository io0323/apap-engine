package apap.infrastructure.jdbc

import apap.domain.event.DomainEvent
import apap.domain.model.modelcatalog.Model
import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.modelcatalog.applyModelEvent
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.port.EventStoreRepository
import apap.domain.port.ModelRepository
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Connection
import javax.sql.DataSource

/**
 * [ModelRepository]のJDBC実装（ADR-0025、ADR-0026）。`findById`等は[EventStoreRepository]経由の
 * 再構築を真の情報源とし、`model`/`model_capability`テーブルは横断検索用のRead Model射影として
 * `save`が同期的に書き込む（[JdbcProviderRepository]と同じ設計判断）。
 */
class JdbcModelRepository(
    private val dataSource: DataSource,
    private val eventStoreRepository: EventStoreRepository,
    private val objectMapper: ObjectMapper = JdbcSupport.objectMapper,
    snapshotEveryNEvents: Int = 100,
) : ModelRepository {
    private val support =
        EventSourcedRepositorySupport(eventStoreRepository, Model::class, ::applyModelEvent, snapshotEveryNEvents)

    override fun findById(id: ModelId): Model? = support.reconstruct(id.value)

    override fun findByProvider(providerId: ProviderId): List<Model> =
        idsWhere("provider_id = ?") { it.setString(1, providerId.value) }.mapNotNull { support.reconstruct(it) }

    override fun findByCapability(capabilityId: CapabilityId): List<Model> =
        idsWhere("model_id IN (SELECT model_id FROM model_capability WHERE capability_id = ?)") {
            it.setString(1, capabilityId.value)
        }.mapNotNull { support.reconstruct(it) }

    @Suppress("NestedBlockDepth")
    private fun idsWhere(
        whereClause: String,
        bind: (java.sql.PreparedStatement) -> Unit,
    ): List<String> {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT model_id FROM model WHERE $whereClause").use { stmt ->
                bind(stmt)
                stmt.executeQuery().use { rs ->
                    val ids = mutableListOf<String>()
                    while (rs.next()) ids += rs.getString("model_id")
                    return ids
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun save(model: Model) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                upsertModel(conn, model)
                replaceCapabilities(conn, model)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    @Suppress("MagicNumber")
    private fun upsertModel(
        conn: Connection,
        model: Model,
    ) {
        conn
            .prepareStatement(
                """
                INSERT INTO model (
                    model_id, provider_id, model_name, version, context_window, max_output_tokens,
                    status, priority, regions
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (model_id) DO UPDATE SET
                    provider_id = EXCLUDED.provider_id, model_name = EXCLUDED.model_name,
                    version = EXCLUDED.version, context_window = EXCLUDED.context_window,
                    max_output_tokens = EXCLUDED.max_output_tokens, status = EXCLUDED.status,
                    priority = EXCLUDED.priority, regions = EXCLUDED.regions
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, model.modelId.value)
                stmt.setString(2, model.providerId.value)
                stmt.setString(3, model.modelName)
                stmt.setString(4, model.version)
                stmt.setInt(5, model.contextWindow)
                stmt.setInt(6, model.maxOutputTokens)
                stmt.setString(7, model.status.name)
                stmt.setInt(8, model.priority)
                stmt.setString(9, objectMapper.writeValueAsString(model.regions.map { it.code }))
                stmt.executeUpdate()
            }
    }

    private fun replaceCapabilities(
        conn: Connection,
        model: Model,
    ) {
        conn.prepareStatement("DELETE FROM model_capability WHERE model_id = ?").use { stmt ->
            stmt.setString(1, model.modelId.value)
            stmt.executeUpdate()
        }
        model.capabilities.forEach { capability -> insertCapability(conn, model.modelId.value, capability) }
    }

    @Suppress("MagicNumber")
    private fun insertCapability(
        conn: Connection,
        modelId: String,
        capability: ModelCapability,
    ) {
        conn
            .prepareStatement(
                "INSERT INTO model_capability (model_id, capability_id, constraints) VALUES (?, ?, ?::jsonb)",
            ).use { stmt ->
                stmt.setString(1, modelId)
                stmt.setString(2, capability.capabilityId.value)
                stmt.setString(3, objectMapper.writeValueAsString(capability.constraints))
                stmt.executeUpdate()
            }
    }

    override fun saveEvents(
        id: ModelId,
        events: List<DomainEvent>,
    ) {
        support.saveEvents(id.value, events)
    }
}
