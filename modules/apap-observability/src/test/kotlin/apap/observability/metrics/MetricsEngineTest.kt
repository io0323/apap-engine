package apap.observability.metrics

import apap.domain.event.CircuitBreakerStateChanged
import apap.domain.event.EventMetadata
import apap.domain.event.FallbackExecuted
import apap.domain.event.ProviderHealthChanged
import apap.domain.event.RateLimitExceeded
import apap.domain.event.RequestCompleted
import apap.domain.event.RequestFailed
import apap.domain.event.RetryExecuted
import apap.domain.event.StreamAborted
import apap.domain.event.StreamClosed
import apap.domain.event.StreamOpened
import apap.domain.model.execution.CbState
import apap.domain.model.provider.Provider
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.CbKey
import apap.domain.model.vo.Cost
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.FinishReason
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RateLimitAction
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.TokenDirection
import apap.domain.model.vo.Usage
import apap.infrastructure.eventbus.SynchronousEventBus
import apap.testkit.inmemory.InMemoryMetricsRecorder
import apap.testkit.inmemory.InMemoryProviderRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class MetricsEngineTest {
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FAA")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FBB")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FCC")
    private val requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FDD")
    private val capabilityId = CapabilityId("chat")
    private val occurredAt = Instant.parse("2026-01-01T00:00:00Z")

    private fun meta(eventId: String) = EventMetadata(eventId, occurredAt, "trace-1", tenantId, requestId.value, 0)

    @Test
    fun `RequestCompleted records requests, duration, tokens, and cost`() {
        val bus = SynchronousEventBus()
        val recorder = InMemoryMetricsRecorder()
        MetricsEngine(bus, recorder, InMemoryProviderRepository())

        val usage = Usage.of(TokenCount(10), TokenCount(4))
        val cost = Cost(Money(BigDecimal("0.05"), "USD"))
        bus.publish(
            RequestCompleted(
                meta("e1"),
                requestId,
                capabilityId,
                providerId.value,
                modelId.value,
                usage,
                cost,
                durationMs = 250,
                finishReason = FinishReason.COMPLETED,
            ),
        )

        val request = recorder.requests.single()
        assertEquals(tenantId, request.tenantId)
        assertEquals(capabilityId, request.capabilityId)
        assertEquals(providerId, request.providerId)
        assertEquals(modelId, request.modelId)
        assertEquals("COMPLETED", request.status)

        assertEquals(0.25, recorder.requestDurations.single().seconds)

        val inTokens = recorder.tokens.single { it.direction == TokenDirection.IN }
        val outTokens = recorder.tokens.single { it.direction == TokenDirection.OUT }
        assertEquals(10L, inTokens.count)
        assertEquals(4L, outTokens.count)

        val recordedCost = recorder.costs.single()
        assertEquals("USD", recordedCost.currency)
        assertEquals(0, BigDecimal("0.05").compareTo(BigDecimal.valueOf(recordedCost.amount)))
    }

    @Test
    fun `RequestFailed records a FAILED request with unknown provider and model`() {
        val bus = SynchronousEventBus()
        val recorder = InMemoryMetricsRecorder()
        MetricsEngine(bus, recorder, InMemoryProviderRepository())

        bus.publish(
            RequestFailed(
                meta("e1"),
                requestId,
                capabilityId,
                ErrorCode.PROVIDER_ERROR,
                attempts = 2,
                fallbacks = 1,
                durationMs = 100,
            ),
        )

        val request = recorder.requests.single()
        assertEquals("FAILED", request.status)
        assertEquals(null, request.providerId)
        assertEquals(null, request.modelId)
    }

    @Test
    fun `RetryExecuted and FallbackExecuted are attributed to the parsed candidate`() {
        val bus = SynchronousEventBus()
        val recorder = InMemoryMetricsRecorder()
        MetricsEngine(bus, recorder, InMemoryProviderRepository())

        val candidateKey = "${providerId.value}:${modelId.value}"
        bus.publish(RetryExecuted(meta("e1"), requestId, candidateKey, attempt = 2, reason = "TIMEOUT"))
        bus.publish(FallbackExecuted(meta("e2"), requestId, candidateKey, "other:candidate", "PROVIDER_UNAVAILABLE"))

        val retry = recorder.retries.single()
        assertEquals(providerId, retry.providerId)
        assertEquals(modelId, retry.modelId)
        assertEquals("TIMEOUT", retry.reason)

        val fallback = recorder.fallbacks.single()
        assertEquals(providerId, fallback.providerId)
        assertEquals("PROVIDER_UNAVAILABLE", fallback.reason)
    }

    @Test
    fun `CircuitBreakerStateChanged records the new state as a gauge value`() {
        val bus = SynchronousEventBus()
        val recorder = InMemoryMetricsRecorder()
        MetricsEngine(bus, recorder, InMemoryProviderRepository())

        bus.publish(
            CircuitBreakerStateChanged(meta("e1"), CbKey(providerId, modelId), CbState.CLOSED, CbState.OPEN),
        )

        val recorded = recorder.circuitBreakerStates.single()
        assertEquals(providerId, recorded.providerId)
        assertEquals(CbState.OPEN, recorded.state)
    }

    @Test
    fun `ProviderHealthChanged is recorded once per region the Provider is registered in`() {
        val bus = SynchronousEventBus()
        val recorder = InMemoryMetricsRecorder()
        val providerRepository = InMemoryProviderRepository()
        val jpEast = Region.of("jp-east", RegionCodeTable(setOf("jp-east", "us-east")))
        val usEast = Region.of("us-east", RegionCodeTable(setOf("jp-east", "us-east")))
        providerRepository.save(
            Provider(
                providerId = providerId,
                name = "test-provider",
                adapterPluginId = "plugin-1",
                spiVersion = SemVer(1, 0, 0),
                endpoints = emptyList(),
                authType = "api_key",
                credentialRefs = listOf(CredentialRef("secret", 1, CredentialState.ACTIVE)),
                rateLimits = RateLimits(60, 100_000, 10),
                priority = 50,
                regions = setOf(jpEast, usEast),
            ),
        )
        MetricsEngine(bus, recorder, providerRepository)

        bus.publish(
            ProviderHealthChanged(
                meta("e1"),
                providerId,
                ProviderHealthStatus.UP,
                ProviderHealthStatus.DOWN,
                "evidence",
            ),
        )

        assertEquals(setOf("jp-east", "us-east"), recorder.providerHealths.map { it.region }.toSet())
        assertEquals(setOf(ProviderHealthStatus.DOWN), recorder.providerHealths.map { it.status }.toSet())
    }

    @Test
    fun `ProviderHealthChanged for an unknown provider records nothing`() {
        val bus = SynchronousEventBus()
        val recorder = InMemoryMetricsRecorder()
        MetricsEngine(bus, recorder, InMemoryProviderRepository())

        bus.publish(
            ProviderHealthChanged(
                meta("e1"),
                providerId,
                ProviderHealthStatus.UP,
                ProviderHealthStatus.DOWN,
                "evidence",
            ),
        )

        assertEquals(true, recorder.providerHealths.isEmpty())
    }

    @Test
    fun `StreamOpened increments and StreamClosed-or-Aborted decrements streaming connections`() {
        val bus = SynchronousEventBus()
        val recorder = InMemoryMetricsRecorder()
        MetricsEngine(bus, recorder, InMemoryProviderRepository())

        bus.publish(StreamOpened(meta("e1"), requestId))
        bus.publish(StreamOpened(meta("e2"), requestId))
        assertEquals(2, recorder.streamingConnectionDelta)

        bus.publish(StreamClosed(meta("e3"), requestId))
        assertEquals(1, recorder.streamingConnectionDelta)

        bus.publish(StreamAborted(meta("e4"), requestId, "idle timeout"))
        assertEquals(0, recorder.streamingConnectionDelta)
    }

    @Test
    fun `RateLimitExceeded is recorded as a reject action`() {
        val bus = SynchronousEventBus()
        val recorder = InMemoryMetricsRecorder()
        MetricsEngine(bus, recorder, InMemoryProviderRepository())

        bus.publish(RateLimitExceeded(meta("e1"), scope = "tenant", tenantId = tenantId))

        val event = recorder.rateLimitEvents.single()
        assertEquals("tenant", event.scope)
        assertEquals(RateLimitAction.REJECT, event.action)
    }

    @Test
    fun `a duplicate eventId delivery does not double-record`() {
        val bus = SynchronousEventBus()
        val recorder = InMemoryMetricsRecorder()
        MetricsEngine(bus, recorder, InMemoryProviderRepository())

        val event = RateLimitExceeded(meta("e1"), scope = "tenant", tenantId = tenantId)
        bus.publish(event)
        bus.publish(event)

        assertEquals(1, recorder.rateLimitEvents.size)
    }
}
