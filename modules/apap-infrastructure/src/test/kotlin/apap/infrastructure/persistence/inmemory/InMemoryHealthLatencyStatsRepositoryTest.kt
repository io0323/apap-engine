package apap.infrastructure.persistence.inmemory

import apap.domain.model.vo.HealthLatencySnapshot
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class InMemoryHealthLatencyStatsRepositoryTest {
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val now = Instant.parse("2026-01-01T00:10:00Z")

    @Test
    fun `snapshot aggregates only outcomes inside the window`() {
        val repo = InMemoryHealthLatencyStatsRepository()
        // Outside the 5-minute window (older than now-5m).
        repo.recordOutcome(providerId, modelId, success = false, latencyMs = 9999, at = now.minus(Duration.ofMinutes(10)))
        // Inside the window.
        repo.recordOutcome(providerId, modelId, success = true, latencyMs = 100, at = now)
        repo.recordOutcome(providerId, modelId, success = true, latencyMs = 200, at = now)

        val snapshot = repo.snapshot(providerId, modelId, now)

        assertEquals(2, snapshot.sampleCount)
        assertEquals(1.0, snapshot.successRate)
    }

    @Test
    fun `snapshot with no recorded outcomes returns EMPTY`() {
        val repo = InMemoryHealthLatencyStatsRepository()

        assertEquals(HealthLatencySnapshot.EMPTY, repo.snapshot(providerId, modelId, now))
    }
}
