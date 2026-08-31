package apap.infrastructure.jdbc

import apap.domain.event.EventMetadata
import apap.domain.event.ModelRegistered
import apap.domain.event.ModelStatusChanged
import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.modelcatalog.applyModelEvent
import apap.domain.model.reconstruct
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.testkit.inmemory.InMemoryClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.sql.DataSource

/**
 * ローカルPostgreSQL（`docker compose -f tools/docker-compose.yaml up -d rdbms`）に対する統合テスト。
 * ADR-0026: `findById`が常にEvent Sourcing経由の再構築であること、再起動後も復元できることを検証する。
 */
class JdbcModelRepositoryTest {
    private lateinit var dataSource: DataSource
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA2")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))

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
                    "VALUES (?, 'test-provider', '01ARZ3NDEKTSV4RRFFQ69G5FAB', '1.0', 'api_key', 50, 'ACTIVE', " +
                    "60, 100000, 10, '[]', now(), now())"
            conn.prepareStatement(insertProvider).use { stmt ->
                stmt.setString(1, providerId.value)
                stmt.executeUpdate()
            }
            conn
                .prepareStatement(
                    "INSERT INTO capability (capability_id, name, input_schema, output_schema, streamable, status) " +
                        "VALUES ('chat', 'chat', '{}', '{}', true, 'ACTIVE')",
                ).use { it.executeUpdate() }
        }
    }

    private fun repo(snapshotEveryNEvents: Int = 100) =
        JdbcModelRepository(
            dataSource,
            JdbcEventStoreRepository(dataSource, InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))),
            snapshotEveryNEvents = snapshotEveryNEvents,
        )

    private fun meta(version: Long) =
        EventMetadata("evt-$version", Instant.parse("2026-01-01T00:00:00Z"), "trace-1", null, modelId.value, version)

    private fun registered() =
        ModelRegistered(
            meta(1),
            modelId,
            providerId,
            listOf(ModelCapability(CapabilityId("chat"))),
            "test-model",
            "1.0",
            8000,
            2000,
            setOf(region),
            50,
        )

    @Test
    fun `findById reconstructs the current state from the appended events`() {
        val repo = repo()
        repo.saveEvents(modelId, listOf(registered()))
        repo.saveEvents(
            modelId,
            listOf(ModelStatusChanged(meta(2), modelId, ModelStatus.REGISTERED, ModelStatus.TESTING)),
        )

        val found = repo.findById(modelId)

        assertEquals(ModelStatus.TESTING, found?.status)
        assertEquals(listOf(ModelCapability(CapabilityId("chat"))), found?.capabilities)
    }

    @Test
    fun `a fresh Repository instance against the same datasource reconstructs the same state (restart scenario)`() {
        val firstProcess = repo()
        firstProcess.saveEvents(modelId, listOf(registered()))
        firstProcess.saveEvents(
            modelId,
            listOf(ModelStatusChanged(meta(2), modelId, ModelStatus.REGISTERED, ModelStatus.TESTING)),
        )

        val afterRestart = repo()
        assertEquals(ModelStatus.TESTING, afterRestart.findById(modelId)?.status)
    }

    @Test
    fun `findByProvider lists models via the read model projection, reconstructed via their own event stream`() {
        val repo = repo()
        repo.save(reconstruct(listOf(registered()), null, ::applyModelEvent)!!)
        repo.saveEvents(modelId, listOf(registered()))

        val found = repo.findByProvider(providerId)

        assertEquals(listOf(modelId), found.map { it.modelId })
    }
}
