package apap.infrastructure.jdbc

import apap.domain.event.EventMetadata
import apap.domain.event.ProviderEnabled
import apap.domain.event.ProviderRegistered
import apap.domain.model.AggregateSnapshot
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/** ローカルPostgreSQL（`docker compose -f tools/docker-compose.yaml up -d rdbms`）に対する統合テスト。ADR-0014。 */
class JdbcEventStoreRepositoryTest {
    private lateinit var repo: JdbcEventStoreRepository
    private val streamId = "01ARZ3NDEKTSV4RRFFQ69G5FA0"
    private val providerId = ProviderId(streamId)

    @BeforeEach
    fun setUp() {
        val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
        repo = JdbcEventStoreRepository(JdbcTestSupport.freshDataSource(), clock)
    }

    private fun meta(version: Long) =
        EventMetadata(
            eventId = "evt-$version",
            occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
            traceId = "trace-1",
            tenantId = null,
            aggregateId = streamId,
            version = version,
        )

    private fun registeredEvent(): ProviderRegistered {
        val version = 1L
        return ProviderRegistered(
            meta(version),
            providerId,
            "test-provider",
            "01ARZ3NDEKTSV4RRFFQ69G5FAB",
            SemVer(1, 0, 0),
            emptyList(),
            "api_key",
            listOf(CredentialRef("secret-ref-1", 1, CredentialState.ACTIVE)),
            RateLimits(60, 60000, 10),
            50,
            setOf(Region.of("us-east-1", RegionCodeTable(setOf("us-east-1")))),
        )
    }

    @Test
    fun `append then read round-trips events with their concrete type, in version order`() {
        repo.append(streamId, listOf(registeredEvent()), expectedVersion = 0)
        repo.append(streamId, listOf(ProviderEnabled(meta(2), providerId, "manual")), expectedVersion = 1)

        val events = repo.read(streamId, fromVersion = 1)

        assertEquals(2, events.size)
        assertEquals(providerId, (events[0] as ProviderRegistered).providerId)
        assertEquals("manual", (events[1] as ProviderEnabled).reason)
        assertEquals(2L, repo.latestVersion(streamId))
    }

    @Test
    fun `read with fromVersion skips earlier events`() {
        repo.append(streamId, listOf(registeredEvent()), expectedVersion = 0)
        repo.append(streamId, listOf(ProviderEnabled(meta(2), providerId, "manual")), expectedVersion = 1)

        val events = repo.read(streamId, fromVersion = 2)

        assertEquals(1, events.size)
        assertEquals("manual", (events.single() as ProviderEnabled).reason)
    }

    @Test
    fun `append with a stale expectedVersion throws EventStreamConcurrencyException and appends nothing`() {
        repo.append(streamId, listOf(registeredEvent()), expectedVersion = 0)

        assertThrows(EventStreamConcurrencyException::class.java) {
            // Stale: the stream is already at version 1, not 0.
            repo.append(streamId, listOf(ProviderEnabled(meta(2), providerId, "manual")), expectedVersion = 0)
        }

        assertEquals(1L, repo.latestVersion(streamId))
    }

    @Test
    fun `saveSnapshot then loadSnapshot round-trips the aggregate state, and unknown streams return null`() {
        val provider = testProvider()
        repo.saveSnapshot(AggregateSnapshot(streamId, version = 3, state = provider))

        val loaded = repo.loadSnapshot(streamId, Provider::class)

        assertEquals(3L, loaded?.version)
        assertEquals(provider, loaded?.state)
        assertNull(repo.loadSnapshot("no-such-stream", Provider::class))
    }

    @Test
    fun `saveSnapshot overwrites the previous snapshot for the same stream`() {
        repo.saveSnapshot(AggregateSnapshot(streamId, version = 1, state = testProvider()))
        val activeProvider = testProvider(status = ProviderStatus.ACTIVE)
        repo.saveSnapshot(AggregateSnapshot(streamId, version = 5, state = activeProvider))

        val loaded = repo.loadSnapshot(streamId, Provider::class)

        assertEquals(5L, loaded?.version)
        assertEquals(ProviderStatus.ACTIVE, loaded?.state?.status)
    }

    private fun testProvider(status: ProviderStatus = ProviderStatus.REGISTERED) =
        Provider(
            providerId = providerId,
            name = "test-provider",
            adapterPluginId = "01ARZ3NDEKTSV4RRFFQ69G5FAB",
            spiVersion = SemVer(1, 0, 0),
            endpoints = emptyList(),
            authType = "api_key",
            credentialRefs = listOf(CredentialRef("secret", 1, CredentialState.STANDBY)),
            rateLimits = RateLimits(rpm = 60, tpm = 100_000, concurrent = 10),
            priority = 50,
            regions = setOf(Region.of("jp-east", RegionCodeTable(setOf("jp-east")))),
            status = status,
        )
}
