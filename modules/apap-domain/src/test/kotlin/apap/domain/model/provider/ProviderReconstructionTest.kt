package apap.domain.model.provider

import apap.domain.event.BatchJobCancelled
import apap.domain.event.EventMetadata
import apap.domain.event.ProviderDraining
import apap.domain.event.ProviderEnabled
import apap.domain.event.ProviderRegistered
import apap.domain.event.ProviderValidated
import apap.domain.model.UnexpectedEventForAggregateException
import apap.domain.model.reconstruct
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

/** ADR-0026: イベント列からのProvider再構築が、コマンド適用後の状態と一致することを検証する。 */
class ProviderReconstructionTest {
    private val providerId = ProviderId(testUlid('A'))
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))

    private fun meta(version: Long) =
        EventMetadata(
            eventId = "evt-$version",
            occurredAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(version),
            traceId = "trace-1",
            tenantId = null,
            aggregateId = providerId.value,
            version = version,
        )

    private fun registered() =
        ProviderRegistered(
            meta(1),
            providerId,
            "test-provider",
            testUlid('B'),
            SemVer(1, 0, 0),
            emptyList(),
            "api_key",
            listOf(CredentialRef("secret-1", 1, CredentialState.STANDBY)),
            RateLimits(60, 100_000, 10),
            50,
            setOf(region),
        )

    private fun validated() = ProviderValidated(meta(2), providerId, 1)

    private fun enabled() = ProviderEnabled(meta(3), providerId, "manual")

    @Test
    fun `reconstruction from the full event list matches the state a live command flow would reach`() {
        val events = listOf(registered(), validated(), enabled())

        val result = reconstruct(events, null, ::applyProviderEvent)

        assertEquals(ProviderStatus.ACTIVE, result?.status)
        assertEquals(
            listOf(CredentialRef("secret-1", 1, CredentialState.ACTIVE)),
            result?.credentialRefs,
        )
    }

    @Test
    fun `draining records drainStartedAt from the event's occurredAt`() {
        val draining = ProviderDraining(meta(4), providerId, "manual")
        val events = listOf(registered(), validated(), enabled(), draining)

        val result = reconstruct(events, null, ::applyProviderEvent)

        assertEquals(ProviderStatus.DRAINING, result?.status)
        assertEquals(draining.meta.occurredAt, result?.drainStartedAt)
    }

    @Test
    fun `reconstructing from a snapshot plus only the events since it matches full replay`() {
        val events = listOf(registered(), validated(), enabled())
        val fullReplay = reconstruct(events, null, ::applyProviderEvent)

        val snapshotState = reconstruct(events.take(2), null, ::applyProviderEvent)
        val fromSnapshot = reconstruct(events.drop(2), snapshotState, ::applyProviderEvent)

        assertEquals(fullReplay, fromSnapshot)
    }

    @Test
    fun `an event that does not belong to Provider throws instead of silently ignoring it`() {
        assertThrows(UnexpectedEventForAggregateException::class.java) {
            applyProviderEvent(
                reconstruct(listOf(registered()), null, ::applyProviderEvent),
                BatchJobCancelled(meta(9), "job-1"),
            )
        }
    }
}
