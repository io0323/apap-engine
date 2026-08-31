package apap.infrastructure.jdbc

import apap.domain.event.AliasChanged
import apap.domain.event.AliasTargetSnapshot
import apap.domain.event.EventMetadata
import apap.domain.model.modelcatalog.AliasTarget
import apap.domain.model.modelcatalog.ModelAlias
import apap.domain.model.vo.AliasId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TenantId
import apap.testkit.inmemory.InMemoryClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.sql.DataSource

/** ローカルPostgreSQL（`docker compose -f tools/docker-compose.yaml up -d rdbms`）に対する統合テスト。 */
class JdbcAliasRepositoryTest {
    private lateinit var dataSource: DataSource
    private val aliasId = AliasId("01ARZ3NDEKTSV4RRFFQ69G5FA4")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA2")
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")

    @BeforeEach
    fun setUp() {
        dataSource = JdbcTestSupport.freshDataSource()
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    "INSERT INTO plugin (plugin_id, name, version, spi_version, signature, status) " +
                        "VALUES ('01ARZ3NDEKTSV4RRFFQ69G5FAB', 'p', '1.0.0', '1.0', 'sig', 'LOADED')",
                ).use { it.executeUpdate() }
            val insertProvider =
                "INSERT INTO provider (provider_id, name, adapter_plugin_id, spi_version, auth_type, priority, " +
                    "status, rate_limit_rpm, rate_limit_tpm, rate_limit_concurrent, regions, created_at, updated_at) " +
                    "VALUES ('01ARZ3NDEKTSV4RRFFQ69G5FA1', 'test-provider', '01ARZ3NDEKTSV4RRFFQ69G5FAB', '1.0', " +
                    "'api_key', 50, 'ACTIVE', 60, 100000, 10, '[]', now(), now())"
            conn.prepareStatement(insertProvider).use { it.executeUpdate() }
            conn
                .prepareStatement(
                    "INSERT INTO model (model_id, provider_id, model_name, version, context_window, " +
                        "max_output_tokens, status, priority) VALUES (?, '01ARZ3NDEKTSV4RRFFQ69G5FA1', 'm', 'v1', " +
                        "8000, 1000, 'ACTIVE', 50)",
                ).use { stmt ->
                    stmt.setString(1, modelId.value)
                    stmt.executeUpdate()
                }
        }
    }

    private fun repo() =
        JdbcAliasRepository(
            dataSource,
            JdbcEventStoreRepository(dataSource, InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))),
        )

    private fun meta(version: Long) =
        EventMetadata("evt-$version", Instant.parse("2026-01-01T00:00:00Z"), "trace-1", null, aliasId.value, version)

    @Test
    fun `findByName reconstructs the aliased model whose event was recorded under this Repository's stream`() {
        val repo = repo()
        repo.save(tenantId, ModelAlias(aliasId, "chat-standard", listOf(AliasTarget(modelId, 100))))
        repo.saveEvents(
            aliasId,
            listOf(
                AliasChanged(
                    meta(1),
                    aliasId.value,
                    "chat-standard",
                    emptyList(),
                    listOf(AliasTargetSnapshot(modelId, 100)),
                ),
            ),
        )

        val found = repo.findByName(tenantId, "chat-standard")

        assertEquals(aliasId, found?.aliasId)
        assertEquals(100, found?.targets?.single()?.weight)
    }

    @Test
    fun `findByName scopes by tenant`() {
        assertNull(repo().findByName(TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA9"), "chat-standard"))
    }
}
