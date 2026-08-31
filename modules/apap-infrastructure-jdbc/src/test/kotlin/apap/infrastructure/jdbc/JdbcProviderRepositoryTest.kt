package apap.infrastructure.jdbc

import apap.domain.event.EventMetadata
import apap.domain.event.ProviderEnabled
import apap.domain.event.ProviderRegistered
import apap.domain.event.ProviderValidated
import apap.domain.model.provider.Provider
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.SemVer
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryIdGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.sql.DataSource

/**
 * ローカルPostgreSQL（`docker compose -f tools/docker-compose.yaml up -d rdbms`）に対する統合テスト。
 * ADR-0026: `findById`が常にEvent Sourcing経由の再構築であること、再起動（Repository作り直し）後も
 * 復元できることを検証する。
 */
class JdbcProviderRepositoryTest {
    private lateinit var dataSource: DataSource
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
        }
    }

    private fun repo(snapshotEveryNEvents: Int = 100) =
        JdbcProviderRepository(
            dataSource,
            JdbcEventStoreRepository(dataSource, InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))),
            InMemoryClock(Instant.parse("2026-01-01T00:00:00Z")),
            InMemoryIdGenerator(),
            snapshotEveryNEvents = snapshotEveryNEvents,
        )

    private fun meta(version: Long) =
        EventMetadata("evt-$version", Instant.parse("2026-01-01T00:00:00Z"), "trace-1", null, providerId.value, version)

    private fun registered() =
        ProviderRegistered(
            meta(1),
            providerId,
            "test-provider",
            "01ARZ3NDEKTSV4RRFFQ69G5FAB",
            SemVer(1, 0, 0),
            emptyList(),
            "api_key",
            listOf(CredentialRef("secret-1", 1, CredentialState.STANDBY)),
            RateLimits(60, 100_000, 10),
            50,
            setOf(region),
        )

    @Test
    fun `findById reconstructs the current state from the appended events`() {
        val repo = repo()
        repo.saveEvents(providerId, listOf(registered()))
        repo.saveEvents(providerId, listOf(ProviderValidated(meta(2), providerId, 1)))
        repo.saveEvents(providerId, listOf(ProviderEnabled(meta(3), providerId, "manual")))

        val found = repo.findById(providerId)

        assertEquals(ProviderStatus.ACTIVE, found?.status)
        assertEquals(CredentialState.ACTIVE, found?.credentialRefs?.single()?.state)
    }

    @Test
    fun `findById returns null for an unknown provider`() {
        assertNull(repo().findById(ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA9")))
    }

    @Test
    fun `crossing the snapshot interval takes a snapshot that a fresh Repository instance can use`() {
        val writer = repo(snapshotEveryNEvents = 2)
        writer.saveEvents(providerId, listOf(registered()))
        writer.saveEvents(providerId, listOf(ProviderValidated(meta(2), providerId, 1)))
        writer.saveEvents(providerId, listOf(ProviderEnabled(meta(3), providerId, "manual")))

        val eventStore = JdbcEventStoreRepository(dataSource, InMemoryClock(Instant.parse("2026-01-01T00:00:00Z")))
        val snapshot = eventStore.loadSnapshot(providerId.value, Provider::class)

        assertTrue(snapshot != null && snapshot.version >= 2)
    }

    @Test
    fun `a fresh Repository instance against the same datasource reconstructs the same state (restart scenario)`() {
        val firstProcess = repo()
        firstProcess.saveEvents(providerId, listOf(registered()))
        firstProcess.saveEvents(providerId, listOf(ProviderValidated(meta(2), providerId, 1)))
        firstProcess.saveEvents(providerId, listOf(ProviderEnabled(meta(3), providerId, "manual")))

        val afterRestart = repo()
        val found = afterRestart.findById(providerId)

        assertEquals(ProviderStatus.ACTIVE, found?.status)
    }

    @Test
    fun `a concurrent append conflict does not corrupt subsequent reconstruction`() {
        val repo = repo()
        repo.saveEvents(providerId, listOf(registered()))
        val eventStore = JdbcEventStoreRepository(dataSource, InMemoryClock(Instant.parse("2026-01-01T00:00:00Z")))

        assertThrows(EventStreamConcurrencyException::class.java) {
            eventStore.append(
                providerId.value,
                listOf(ProviderEnabled(meta(1), providerId, "manual")),
                expectedVersion = 0,
            )
        }

        repo.saveEvents(providerId, listOf(ProviderEnabled(meta(2), providerId, "manual")))
        assertEquals(ProviderStatus.ACTIVE, repo.findById(providerId)?.status)
    }
}
