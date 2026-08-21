package apap.domain.model.conversation

import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class SessionAndMemoryTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun session(
        status: SessionStatus = SessionStatus.ACTIVE,
        expiresAt: Instant = now.plusSeconds(3600),
    ) = Session(
        sessionId = SessionId(testUlid('A')),
        tenantId = TenantId(testUlid('B')),
        principal = "user-1",
        expiresAt = expiresAt,
        status = status,
    )

    @Test
    fun `requireUsable passes for an ACTIVE non-expired session`() {
        assertDoesNotThrow { session().requireUsable(now) }
    }

    @Test
    fun `requireUsable throws for an EXPIRED session`() {
        val expired = session(status = SessionStatus.EXPIRED)
        assertThrows(SessionNotUsableException::class.java) { expired.requireUsable(now) }
    }

    @Test
    fun `requireUsable throws once the expiry instant has passed`() {
        assertThrows(SessionNotUsableException::class.java) {
            session(expiresAt = now.minusSeconds(1)).requireUsable(now)
        }
    }

    @Test
    fun `expire and revoke change status`() {
        assertEquals(SessionStatus.EXPIRED, session().expire().status)
        assertEquals(SessionStatus.REVOKED, session().revoke().status)
    }

    @Test
    fun `Session rejects blank principal`() {
        assertThrows(IllegalArgumentException::class.java) {
            Session(SessionId(testUlid('A')), TenantId(testUlid('B')), "  ", expiresAt = now.plusSeconds(1))
        }
    }

    private fun memory() =
        Memory(
            memoryId = "mem-1",
            tenantId = TenantId(testUlid('A')),
            scope = MemoryScope.USER,
            content = "remember this",
            embedding = listOf(0.1, 0.2, 0.3),
            importance = 0.5,
            lastAccessedAt = now,
        )

    @Test
    fun `Memory rejects importance outside 0 to 1`() {
        assertThrows(IllegalArgumentException::class.java) { memory().copy(importance = 1.5) }
        assertThrows(IllegalArgumentException::class.java) { memory().copy(importance = -0.1) }
    }

    @Test
    fun `Memory rejects empty embedding or blank content`() {
        assertThrows(IllegalArgumentException::class.java) { memory().copy(embedding = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { memory().copy(content = " ") }
    }

    @Test
    fun `touch updates lastAccessedAt`() {
        val touched = memory().touch(now.plusSeconds(10))
        assertEquals(now.plusSeconds(10), touched.lastAccessedAt)
    }
}
