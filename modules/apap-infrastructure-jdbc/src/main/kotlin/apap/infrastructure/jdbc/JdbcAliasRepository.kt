package apap.infrastructure.jdbc

import apap.domain.event.DomainEvent
import apap.domain.model.modelcatalog.AliasTarget
import apap.domain.model.modelcatalog.ModelAlias
import apap.domain.model.modelcatalog.applyModelAliasEvent
import apap.domain.model.vo.AliasId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TenantId
import apap.domain.port.AliasRepository
import apap.domain.port.EventStoreRepository
import java.sql.Connection
import javax.sql.DataSource

/**
 * [AliasRepository]のJDBC実装（ADR-0025、ADR-0026）。`findByName`/`listByModel`は横断検索用の
 * `model_alias`/`alias_target`テーブル（Read Model射影、`save`が同期的に書き込む）を読む。
 * それ以外の再構築は行わない（[AliasRepository]のPortにはfindByIdが無いため、単一Aliasの
 * イベント再生は[saveEvents]の内部処理のみで完結する。[JdbcProviderRepository]と同じ設計判断）。
 */
class JdbcAliasRepository(
    private val dataSource: DataSource,
    private val eventStoreRepository: EventStoreRepository,
    snapshotEveryNEvents: Int = 100,
) : AliasRepository {
    private val support =
        EventSourcedRepositorySupport(
            eventStoreRepository,
            ModelAlias::class,
            ::applyModelAliasEvent,
            snapshotEveryNEvents,
        )

    @Suppress("NestedBlockDepth")
    override fun findByName(
        tenantId: TenantId,
        name: String,
    ): ModelAlias? {
        val aliasId =
            dataSource.connection.use { conn ->
                conn
                    .prepareStatement("SELECT alias_id FROM model_alias WHERE tenant_id = ? AND name = ?")
                    .use { stmt ->
                        stmt.setString(1, tenantId.value)
                        stmt.setString(2, name)
                        stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("alias_id") else null }
                    }
            } ?: return null
        return support.reconstruct(aliasId)
    }

    @Suppress("NestedBlockDepth")
    override fun listByModel(modelId: ModelId): List<ModelAlias> {
        val aliasIds =
            dataSource.connection.use { conn ->
                conn
                    .prepareStatement("SELECT DISTINCT alias_id FROM alias_target WHERE model_id = ?")
                    .use { stmt ->
                        stmt.setString(1, modelId.value)
                        stmt.executeQuery().use { rs ->
                            val ids = mutableListOf<String>()
                            while (rs.next()) ids += rs.getString("alias_id")
                            ids
                        }
                    }
            }
        return aliasIds.mapNotNull { support.reconstruct(it) }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun save(
        tenantId: TenantId,
        alias: ModelAlias,
    ) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                upsertAlias(conn, tenantId, alias)
                replaceTargets(conn, alias)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    @Suppress("MagicNumber")
    private fun upsertAlias(
        conn: Connection,
        tenantId: TenantId,
        alias: ModelAlias,
    ) {
        conn
            .prepareStatement(
                """
                INSERT INTO model_alias (alias_id, name, tenant_id) VALUES (?, ?, ?)
                ON CONFLICT (alias_id) DO UPDATE SET name = EXCLUDED.name, tenant_id = EXCLUDED.tenant_id
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, alias.aliasId.value)
                stmt.setString(2, alias.name)
                stmt.setString(3, tenantId.value)
                stmt.executeUpdate()
            }
    }

    private fun replaceTargets(
        conn: Connection,
        alias: ModelAlias,
    ) {
        conn.prepareStatement("DELETE FROM alias_target WHERE alias_id = ?").use { stmt ->
            stmt.setString(1, alias.aliasId.value)
            stmt.executeUpdate()
        }
        alias.targets.forEach { target -> insertTarget(conn, alias.aliasId.value, target) }
    }

    @Suppress("MagicNumber")
    private fun insertTarget(
        conn: Connection,
        aliasId: String,
        target: AliasTarget,
    ) {
        conn
            .prepareStatement("INSERT INTO alias_target (alias_id, model_id, weight) VALUES (?, ?, ?)")
            .use { stmt ->
                stmt.setString(1, aliasId)
                stmt.setString(2, target.modelId.value)
                stmt.setInt(3, target.weight)
                stmt.executeUpdate()
            }
    }

    override fun saveEvents(
        id: AliasId,
        events: List<DomainEvent>,
    ) {
        support.saveEvents(id.value, events)
    }
}
