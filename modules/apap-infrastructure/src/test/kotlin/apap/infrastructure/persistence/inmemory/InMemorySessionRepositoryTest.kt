package apap.infrastructure.persistence.inmemory

import apap.domain.model.conversation.Session
import apap.domain.model.conversation.SessionStatus
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemorySessionRepositoryTest {
    private val sessionId = SessionId("01ARZ3NDEKTSV4RRFFQ69G5FA0")

    private fun session() =
        Session(
            sessionId = sessionId,
            tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA1"),
            principal = "user-1",
            expiresAt = Instant.parse("2027-01-01T00:00:00Z"),
        )

    @Test
    fun `saves and finds a session`() {
        val repo = InMemorySessionRepository()
        repo.save(session())

        assertEquals(SessionStatus.ACTIVE, repo.findById(sessionId)?.status)
    }

    @Test
    fun `expire transitions a saved session to EXPIRED`() {
        val repo = InMemorySessionRepository()
        repo.save(session())

        repo.expire(sessionId)

        assertEquals(SessionStatus.EXPIRED, repo.findById(sessionId)?.status)
    }
}
