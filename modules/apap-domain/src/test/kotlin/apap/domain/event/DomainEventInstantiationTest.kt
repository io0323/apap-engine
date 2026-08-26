package apap.domain.event

import apap.domain.model.execution.CbState
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.CbKey
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.Cost
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.FinishReason
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * 14_イベント一覧.md 全50イベントについて、実際に生成できること・共通属性(meta)を
 * 正しく保持することを検証する。[DomainEventCoverageTest] は型としての存在のみを検証するため、
 * こちらはコンストラクタ・共通属性委譲が実際に機能することを確認する。
 */
class DomainEventInstantiationTest {
    private val tenantId = TenantId(testUlid('A'))
    private val providerId = ProviderId(testUlid('B'))
    private val modelId = ModelId(testUlid('C'))
    private val requestId = RequestId(testUlid('D'))
    private val conversationId = ConversationId(testUlid('E'))
    private val sessionId = SessionId(testUlid('F'))
    private val capabilityId = CapabilityId("chat")
    private val usage = Usage.of(TokenCount(1), TokenCount(1))
    private val cost = Cost(Money(BigDecimal("0.01"), "USD"))

    private fun meta(aggregateId: String) =
        EventMetadata(
            eventId = testUlid('1'),
            occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
            traceId = "trace-1",
            tenantId = tenantId,
            aggregateId = aggregateId,
            version = 1,
        )

    @Test
    fun `every event carries the common metadata via the meta property`() {
        val event = ProviderRegistered(meta(providerId.value), providerId, "test-provider", "plugin-1")
        assertEquals(meta(providerId.value).eventId, event.meta.eventId)
        assertEquals(meta(providerId.value).occurredAt, event.meta.occurredAt)
        assertEquals(tenantId, event.meta.tenantId)
        assertEquals(1L, event.meta.version)
        assertEquals(providerId, event.providerId)
    }

    @Test
    fun `all 14_1 provider model plugin events can be constructed`() {
        ProviderRegistered(meta(providerId.value), providerId, "p", "plugin-1")
        ProviderValidated(meta(providerId.value), providerId)
        ProviderEnabled(meta(providerId.value), providerId, "manual")
        ProviderDraining(meta(providerId.value), providerId, "manual")
        ProviderDisabled(meta(providerId.value), providerId, "manual")
        ProviderDeleted(meta(providerId.value), providerId)
        ProviderHealthChanged(
            meta(providerId.value),
            providerId,
            ProviderHealthStatus.UP,
            ProviderHealthStatus.DOWN,
            "3 consecutive failures",
        )
        ModelRegistered(meta(modelId.value), modelId, providerId, listOf(capabilityId))
        ModelStatusChanged(meta(modelId.value), modelId, ModelStatus.TESTING, ModelStatus.ACTIVE)
        ModelDiscovered(meta(providerId.value), providerId, listOf("new-model-x"))
        AliasChanged(
            meta("alias-1"),
            "alias-1",
            listOf(AliasTargetSnapshot(modelId, 100)),
            listOf(AliasTargetSnapshot(modelId, 90)),
        )
        PluginLoaded(meta("plugin-1"), "plugin-1", "1.0.0")
        PluginUnloaded(meta("plugin-1"), "plugin-1")
        PluginQuarantined(meta("plugin-1"), "plugin-1", "signature mismatch")
    }

    @Test
    fun `all 14_2 execution events can be constructed`() {
        RequestReceived(
            meta(requestId.value),
            requestId,
            capabilityId,
            tenantId,
            "principal-1",
            "alias-1",
            conversationId,
        )
        RequestStarted(
            meta(requestId.value),
            requestId,
            capabilityId,
            tenantId,
            "policy=null; chain=p:m; reason=selected",
        )
        RequestCompleted(
            meta(requestId.value),
            requestId,
            providerId.value,
            modelId.value,
            usage,
            cost,
            120,
            FinishReason.COMPLETED,
            retries = 1,
            fallbacks = 0,
            requestBody = "[input]",
            responseBody = "[output]",
        )
        RequestFailed(
            meta(requestId.value),
            requestId,
            ErrorCode.PROVIDER_ERROR,
            3,
            1,
            durationMs = 120,
            requestBody = "[input]",
        )
        RequestCancelled(meta(requestId.value), requestId, usage)
        RetryExecuted(meta(requestId.value), requestId, "${providerId.value}:${modelId.value}", 2, "TRANSIENT")
        FallbackExecuted(meta(requestId.value), requestId, "candidate-1", "candidate-2", "PROVIDER_UNAVAILABLE")
        CircuitBreakerStateChanged(meta("cb-1"), CbKey(providerId, modelId), CbState.CLOSED, CbState.OPEN)
        CacheHit(meta(requestId.value), requestId, CacheType.RESPONSE)
        CacheStored(meta(requestId.value), requestId, CacheType.REQUEST)
        StreamOpened(meta(requestId.value), requestId)
        StreamClosed(meta(requestId.value), requestId)
        StreamAborted(meta(requestId.value), requestId, "idle timeout")
    }

    @Test
    fun `all 14_3 limit and cost events can be constructed`() {
        RateLimitExceeded(meta("scope-1"), "tenant", tenantId, providerId)
        TokenLimitExceeded(meta(requestId.value), requestId, TokenCount(8000), TokenCount(8500))
        QuotaExceeded(meta(tenantId.value), tenantId, "quota-1", "tokens")
        CostThresholdExceeded(meta(tenantId.value), tenantId, "budget-1", 80, Money(BigDecimal("80.00"), "USD"))
        BudgetPeriodReset(meta("budget-1"), "budget-1")
    }

    @Test
    fun `all 14_4 security and config events can be constructed`() {
        CredentialRotated(meta(providerId.value), providerId, 1, 2)
        CredentialValidationFailed(meta(providerId.value), providerId, "invalid signature")
        PolicyUpdated(meta("policy-1"), "policy-1", "TENANT")
        QuotaPolicyUpdated(meta("quota-1"), "quota-1")
        BudgetUpdated(meta("budget-1"), "budget-1")
        AccessDenied(meta(requestId.value), requestId, "PERMISSION_DENIED")
    }

    @Test
    fun `all 14_5 batch and session events can be constructed`() {
        BatchJobSubmitted(meta("job-1"), "job-1", capabilityId)
        BatchJobStarted(meta("job-1"), "job-1")
        BatchItemCompleted(meta("job-1"), "job-1", "item-1", "COMPLETED")
        BatchJobCompleted(meta("job-1"), "job-1", 10, 10)
        BatchJobFailed(meta("job-1"), "job-1", "fatal error")
        BatchJobCancelled(meta("job-1"), "job-1")
        SessionCreated(meta(sessionId.value), sessionId, "user-1")
        SessionExpired(meta(sessionId.value), sessionId)
        SessionRevoked(meta(sessionId.value), sessionId, "manual revoke")
        ConversationDeleted(meta(conversationId.value), conversationId)
        MemoryStored(meta("mem-1"), "mem-1", "user")
        MemoryDeleted(meta("mem-1"), "mem-1")
    }
}
