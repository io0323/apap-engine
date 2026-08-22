package apap.context

import apap.domain.event.SessionCreated
import apap.domain.event.SessionExpired
import apap.domain.event.SessionRevoked
import apap.domain.model.conversation.SessionNotUsableException
import apap.domain.model.conversation.SessionStatus
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import apap.testkit.inmemory.InMemorySessionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** 02_システム仕様.md 2.15: Session発行/検証/失効。 */
class SessionManagerTest {
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val repository = InMemorySessionRepository()
    private val ids = InMemoryIdGenerator()
    private val events = InMemoryDomainEventPublisher()

    private fun manager(config: SessionManagerConfig = SessionManagerConfig()): SessionManager =
        SessionManager(repository, clock, ids, events, config)

    @Test
    fun `issue creates an ACTIVE session with the configured default TTL and publishes SessionCreated`() {
        val config = SessionManagerConfig(defaultTtl = Duration.ofHours(24))
        val session = manager(config).issue(tenantId, "user-1", "trace-1")
        assertEquals(SessionStatus.ACTIVE, session.status)
        assertEquals(clock.now().plus(Duration.ofHours(24)), session.expiresAt)
        assertTrue(events.publishedEvents.filterIsInstance<SessionCreated>().any { it.sessionId == session.sessionId })
    }

    @Test
    fun `verify rejects usage after the session has expired`() {
        val config = SessionManagerConfig(defaultTtl = Duration.ofMinutes(10))
        val session = manager(config).issue(tenantId, "user-1", "trace-1")
        clock.advanceBy(Duration.ofMinutes(11).seconds)

        assertThrows(SessionNotUsableException::class.java) {
            manager().verify(session.sessionId, "trace-2")
        }
        assertTrue(events.publishedEvents.filterIsInstance<SessionExpired>().any { it.sessionId == session.sessionId })
    }

    @Test
    fun `verify rejects usage after the session has been revoked`() {
        val manager = manager()
        val session = manager.issue(tenantId, "user-1", "trace-1")
        manager.revoke(session.sessionId, "compromised", "trace-2")

        assertThrows(SessionNotUsableException::class.java) {
            manager.verify(session.sessionId, "trace-3")
        }
        assertTrue(events.publishedEvents.filterIsInstance<SessionRevoked>().any { it.sessionId == session.sessionId })
    }

    @Test
    fun `verify extends expiresAt when sliding refresh is enabled`() {
        val manager = manager(SessionManagerConfig(defaultTtl = Duration.ofHours(1), slidingRefresh = true))
        val session = manager.issue(tenantId, "user-1", "trace-1")
        clock.advanceBy(Duration.ofMinutes(30).seconds)

        val refreshed = manager.verify(session.sessionId, "trace-2")
        assertEquals(clock.now().plus(Duration.ofHours(1)), refreshed.expiresAt)
    }

    @Test
    fun `verify does not extend expiresAt when sliding refresh is disabled`() {
        val manager = manager(SessionManagerConfig(defaultTtl = Duration.ofHours(1), slidingRefresh = false))
        val session = manager.issue(tenantId, "user-1", "trace-1")
        clock.advanceBy(Duration.ofMinutes(30).seconds)

        val verified = manager.verify(session.sessionId, "trace-2")
        assertEquals(session.expiresAt, verified.expiresAt)
    }

    @Test
    fun `verify throws for an unknown sessionId`() {
        assertThrows(SessionNotFoundException::class.java) {
            manager().verify(SessionId("01ARZ3NDEKTSV4RRFFQ69G5FAZ"), "trace-1")
        }
    }
}
