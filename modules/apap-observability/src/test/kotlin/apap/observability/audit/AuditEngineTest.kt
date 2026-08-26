package apap.observability.audit

import apap.domain.event.EventMetadata
import apap.domain.event.RequestCompleted
import apap.domain.event.RequestFailed
import apap.domain.event.RequestReceived
import apap.domain.event.RequestStarted
import apap.domain.model.audit.AuditSearchCriteria
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.Cost
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.FinishReason
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.infrastructure.eventbus.SynchronousEventBus
import apap.testkit.inmemory.InMemoryAuditRepository
import apap.testkit.inmemory.InMemoryIdGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class AuditEngineTest {
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FAA")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FBB")
    private val requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FDD")
    private val conversationId = ConversationId("01ARZ3NDEKTSV4RRFFQ69G5FEE")
    private val capabilityId = CapabilityId("chat")
    private val occurredAt = Instant.parse("2026-01-01T00:00:00Z")
    private val usage = Usage.of(TokenCount(10), TokenCount(5))
    private val cost = Cost(Money(BigDecimal("0.02"), "USD"))

    private fun meta(eventId: String) = EventMetadata(eventId, occurredAt, "trace-1", tenantId, requestId.value, 0)

    @Test
    fun `bodyStorageOptIn without a masking strategy is rejected at construction`() {
        val bus = SynchronousEventBus()
        assertThrows(IllegalArgumentException::class.java) {
            AuditEngine(bus, InMemoryAuditRepository(), AuditConfig(bodyStorageOptIn = true), InMemoryIdGenerator())
        }
    }

    @Test
    fun `correlates RequestReceived through RequestStarted to RequestCompleted into one AuditRecord`() {
        val bus = SynchronousEventBus()
        val repository = InMemoryAuditRepository()
        val engine = AuditEngine(bus, repository, AuditConfig(), InMemoryIdGenerator())

        bus.publish(
            RequestReceived(meta("e1"), requestId, capabilityId, tenantId, "user-1", "alias-1", conversationId),
        )
        bus.publish(RequestStarted(meta("e2"), requestId, capabilityId, tenantId, "policy=p1; chain=x:y; reason=ok"))
        bus.publish(
            RequestCompleted(
                meta("e3"),
                requestId,
                capabilityId,
                providerId.value,
                "01ARZ3NDEKTSV4RRFFQ69G5FCC",
                usage,
                cost,
                durationMs = 120,
                finishReason = FinishReason.COMPLETED,
                retries = 2,
                fallbacks = 1,
                requestBody = "hello",
                responseBody = "world",
            ),
        )
        engine.awaitQuiescence()

        val record =
            repository.search(AuditSearchCriteria()).single()
        assertEquals(requestId, record.requestId)
        assertEquals(tenantId, record.tenantId)
        assertEquals("user-1", record.principal)
        assertEquals("chat", record.capabilityId)
        assertEquals("alias-1", record.modelAlias)
        assertEquals(conversationId, record.conversationId)
        assertEquals(providerId, record.providerId)
        assertEquals("policy=p1; chain=x:y; reason=ok", record.routingDecision)
        assertEquals(2, record.retries)
        assertEquals(1, record.fallbacks)
        assertEquals("COMPLETED", record.status)
        assertNull(record.requestBody, "body storage is opt-in and defaults off")
        assertTrue(record.requestDigest.isNotBlank())
        assertEquals(64, record.requestDigest.length, "sha-256 hex digest")
    }

    @Test
    fun `stores masked body only when bodyStorageOptIn is enabled`() {
        val bus = SynchronousEventBus()
        val repository = InMemoryAuditRepository()
        val masking = MaskingStrategy { text -> text.replace("secret", "[MASKED]") }
        val config = AuditConfig(bodyStorageOptIn = true, maskingStrategy = masking)
        val engine = AuditEngine(bus, repository, config, InMemoryIdGenerator())

        bus.publish(RequestReceived(meta("e1"), requestId, capabilityId, tenantId, "user-1"))
        bus.publish(RequestStarted(meta("e2"), requestId, capabilityId, tenantId, "policy=null; chain=x:y; reason=ok"))
        bus.publish(
            RequestCompleted(
                meta("e3"),
                requestId,
                capabilityId,
                providerId.value,
                "01ARZ3NDEKTSV4RRFFQ69G5FCC",
                usage,
                cost,
                durationMs = 50,
                finishReason = FinishReason.COMPLETED,
                requestBody = "contains secret data",
            ),
        )
        engine.awaitQuiescence()

        val record =
            repository.search(AuditSearchCriteria()).single()
        assertEquals("contains [MASKED] data", record.requestBody)
    }

    @Test
    fun `RequestFailed produces an AuditRecord with the failure status and error code`() {
        val bus = SynchronousEventBus()
        val repository = InMemoryAuditRepository()
        val engine = AuditEngine(bus, repository, AuditConfig(), InMemoryIdGenerator())

        bus.publish(RequestReceived(meta("e1"), requestId, capabilityId, tenantId, "user-1"))
        bus.publish(
            RequestFailed(
                meta("e2"),
                requestId,
                capabilityId,
                ErrorCode.PROVIDER_ERROR,
                attempts = 3,
                fallbacks = 1,
                durationMs = 80,
                requestBody = "hello",
            ),
        )
        engine.awaitQuiescence()

        val record =
            repository.search(AuditSearchCriteria()).single()
        assertEquals("FAILED", record.status)
        assertEquals(ErrorCode.PROVIDER_ERROR, record.errorCode)
        assertEquals(3, record.retries)
        assertNull(record.providerId)
    }

    @Test
    fun `a duplicate eventId delivery does not append the audit record twice`() {
        val bus = SynchronousEventBus()
        val repository = InMemoryAuditRepository()
        val engine = AuditEngine(bus, repository, AuditConfig(), InMemoryIdGenerator())

        val received = RequestReceived(meta("e1"), requestId, capabilityId, tenantId, "user-1")
        val completed =
            RequestCompleted(
                meta("e2"),
                requestId,
                capabilityId,
                providerId.value,
                "01ARZ3NDEKTSV4RRFFQ69G5FCC",
                usage,
                cost,
                durationMs = 10,
                finishReason = FinishReason.COMPLETED,
            )
        bus.publish(received)
        bus.publish(completed)
        bus.publish(completed)
        engine.awaitQuiescence()

        assertEquals(
            1,
            repository.search(AuditSearchCriteria()).size,
        )
    }

    @Test
    fun `a missing RequestReceived correlation is skipped rather than fabricated`() {
        val bus = SynchronousEventBus()
        val repository = InMemoryAuditRepository()
        val engine = AuditEngine(bus, repository, AuditConfig(), InMemoryIdGenerator())

        bus.publish(
            RequestCompleted(
                meta("e1"),
                requestId,
                capabilityId,
                providerId.value,
                "01ARZ3NDEKTSV4RRFFQ69G5FCC",
                usage,
                cost,
                durationMs = 10,
                finishReason = FinishReason.COMPLETED,
            ),
        )
        engine.awaitQuiescence()

        assertFalse(
            repository
                .search(
                    AuditSearchCriteria(),
                ).isNotEmpty(),
        )
    }
}
