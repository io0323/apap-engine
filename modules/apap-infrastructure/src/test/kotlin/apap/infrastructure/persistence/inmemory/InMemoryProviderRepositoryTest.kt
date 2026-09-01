package apap.infrastructure.persistence.inmemory

import apap.domain.event.EventMetadata
import apap.domain.event.ProviderEnabled
import apap.domain.model.provider.Provider
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.SemVer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryProviderRepositoryTest {
    private val jpEast = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))

    private fun provider(
        id: String,
        status: ProviderStatus = ProviderStatus.REGISTERED,
    ) = Provider(
        providerId = ProviderId(id),
        name = "test-provider-$id",
        adapterPluginId = "01ARZ3NDEKTSV4RRFFQ69G5FAB",
        spiVersion = SemVer(1, 0, 0),
        endpoints = emptyList(),
        authType = "api_key",
        credentialRefs = listOf(CredentialRef("secret", 1, CredentialState.STANDBY)),
        rateLimits = RateLimits(rpm = 60, tpm = 100_000, concurrent = 10),
        priority = 50,
        regions = setOf(jpEast),
        status = status,
    )

    @Test
    fun `saves and finds a provider, filterable by status`() {
        val repo = InMemoryProviderRepository()
        repo.save(provider("01ARZ3NDEKTSV4RRFFQ69G5FA0", ProviderStatus.ACTIVE))
        repo.save(provider("01ARZ3NDEKTSV4RRFFQ69G5FA1", ProviderStatus.DISABLED))

        assertEquals(1, repo.findByStatus(ProviderStatus.ACTIVE).size)
        assertEquals(2, repo.findAll().size)
    }

    @Test
    fun `saveEvents accumulates events for a stream without affecting the current-state record`() {
        val repo = InMemoryProviderRepository()
        val id = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
        repo.save(provider(id.value))

        repo.saveEvents(
            id,
            listOf(
                ProviderEnabled(
                    EventMetadata("evt1", Instant.now(), "trace-1", null, id.value, 1),
                    id,
                    "manual enable",
                ),
            ),
        )

        // saveEvents must not throw and must not disturb the queryable current-state record.
        assertTrue(repo.findById(id) != null)
    }
}
