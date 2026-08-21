package apap.domain.model

import apap.domain.model.provider.Provider
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** ADR-0014: EventStoreRepository.saveSnapshot/loadSnapshotが運ぶペイロード。 */
class AggregateSnapshotTest {
    @Test
    fun `holds the streamId, version and the Aggregate state itself`() {
        val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
        val provider =
            Provider(
                providerId = ProviderId(testUlid('A')),
                name = "test-provider",
                adapterPluginId = testUlid('B'),
                spiVersion = SemVer(1, 0, 0),
                endpoints = emptyList(),
                authType = "api_key",
                credentialRefs = listOf(CredentialRef("secret", 1, CredentialState.STANDBY)),
                rateLimits = RateLimits(rpm = 60, tpm = 1000, concurrent = 5),
                priority = 50,
                regions = setOf(region),
            )

        val snapshot = AggregateSnapshot(streamId = provider.providerId.value, version = 12L, state = provider)

        assertEquals(provider.providerId.value, snapshot.streamId)
        assertEquals(12L, snapshot.version)
        assertEquals(provider, snapshot.state)
    }
}
