package apap.routing

import apap.domain.event.EventMetadata
import apap.domain.event.ProviderDisabled
import apap.domain.event.ProviderDraining
import apap.domain.event.ProviderEnabled
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.vo.ProviderId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/** Provider/Modelの状態遷移がRouting候補へイベント経由で即時反映されること。eventIdによる冪等処理。 */
class RoutingCandidateCacheTest {
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FAX")

    private val occurredAt = Instant.parse("2026-01-01T00:00:00Z")

    private fun meta(eventId: String) = EventMetadata(eventId, occurredAt, "trace", null, providerId.value, 0)

    @Test
    fun `unseen providers report unknown status`() {
        val cache = RoutingCandidateCache()

        assertNull(cache.providerStatus(providerId))
    }

    @Test
    fun `ProviderEnabled and ProviderDraining are reflected immediately`() {
        val cache = RoutingCandidateCache()

        cache.apply(ProviderEnabled(meta("evt-1"), providerId, "manual"))
        assertEquals(ProviderStatus.ACTIVE, cache.providerStatus(providerId))

        cache.apply(ProviderDraining(meta("evt-2"), providerId, "maintenance"))
        assertEquals(ProviderStatus.DRAINING, cache.providerStatus(providerId))
    }

    @Test
    fun `re-delivering the same eventId is idempotent and does not reapply the event`() {
        val cache = RoutingCandidateCache()
        cache.apply(ProviderEnabled(meta("evt-1"), providerId, "manual"))

        // 同一eventIdでの再配送（at-least-once配送のEvent Busを想定）は無視される。
        cache.apply(ProviderDisabled(meta("evt-1"), providerId, "should be ignored"))

        assertEquals(ProviderStatus.ACTIVE, cache.providerStatus(providerId))
    }
}
