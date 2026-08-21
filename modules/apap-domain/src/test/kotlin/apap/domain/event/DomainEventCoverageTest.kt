package apap.domain.event

import apap.domain.architecture.assertScopeNotEmpty
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue as junitAssertTrue

/**
 * 14_イベント一覧.md 全イベントが型として存在することを検証する。
 * 表の名称とコードの対応が将来ずれても機械的に検出できるようにする（勝手なリネーム禁止の担保）。
 */
class DomainEventCoverageTest {
    // 14.1 Provider / Model / Plugin系
    // 14.2 リクエスト実行系
    // 14.3 制限・コスト系
    // 14.4 セキュリティ・構成系
    // 14.5 Batch / Session系
    private val expectedEventNames =
        setOf(
            // 14.1
            "ProviderRegistered",
            "ProviderValidated",
            "ProviderEnabled",
            "ProviderDraining",
            "ProviderDisabled",
            "ProviderDeleted",
            "ProviderHealthChanged",
            "ModelRegistered",
            "ModelStatusChanged",
            "ModelDiscovered",
            "AliasChanged",
            "PluginLoaded",
            "PluginUnloaded",
            "PluginQuarantined",
            // 14.2
            "RequestReceived",
            "RequestStarted",
            "RequestCompleted",
            "RequestFailed",
            "RequestCancelled",
            "RetryExecuted",
            "FallbackExecuted",
            "CircuitBreakerStateChanged",
            "CacheHit",
            "CacheStored",
            "StreamOpened",
            "StreamClosed",
            "StreamAborted",
            // 14.3
            "RateLimitExceeded",
            "TokenLimitExceeded",
            "QuotaExceeded",
            "CostThresholdExceeded",
            "BudgetPeriodReset",
            // 14.4
            "CredentialRotated",
            "CredentialValidationFailed",
            "PolicyUpdated",
            "QuotaPolicyUpdated",
            "BudgetUpdated",
            "AccessDenied",
            // 14.5
            "BatchJobSubmitted",
            "BatchJobStarted",
            "BatchItemCompleted",
            "BatchJobCompleted",
            "BatchJobFailed",
            "BatchJobCancelled",
            "SessionCreated",
            "SessionExpired",
            "SessionRevoked",
            "ConversationDeleted",
            "MemoryStored",
            "MemoryDeleted",
        )

    @Test
    fun `14 chapter lists exactly 50 events`() {
        junitAssertTrue(expectedEventNames.size == 50) { "expected 50 event names, found ${expectedEventNames.size}" }
    }

    @Test
    fun `every event name from chapter 14 exists as a DomainEvent implementation`() {
        val scope = Konsist.scopeFromPackage("apap.domain.event")
        assertScopeNotEmpty(scope, "apap.domain.event")
        val declaredNames =
            scope
                .classes()
                .filter { it.hasParentWithName("DomainEvent") }
                .map { it.name }
                .toSet()

        val missing = expectedEventNames - declaredNames
        junitAssertTrue(missing.isEmpty()) { "Missing DomainEvent implementations for: $missing" }
    }

    @Test
    fun `every DomainEvent implementation is present in the chapter 14 name list`() {
        val scope = Konsist.scopeFromPackage("apap.domain.event")
        assertScopeNotEmpty(scope, "apap.domain.event")
        scope
            .classes()
            .filter { it.hasParentWithName("DomainEvent") }
            .assertTrue(strict = true) { it.name in expectedEventNames }
    }
}
