package apap.execution.attempt

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.mock.MockProviderAdapter
import apap.adapter.mock.ScriptedOutcome
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.cache.ratelimit.RateLimiterConfig
import apap.cache.ratelimit.TokenBucketRateLimiter
import apap.domain.model.execution.CbState
import apap.domain.model.execution.ExecutionContext
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.CbKey
import apap.domain.model.vo.CredentialRef
import apap.execution.adapter.out.InMemoryCircuitBreakerStateStore
import apap.execution.circuitbreaker.CircuitBreaker
import apap.execution.circuitbreaker.CircuitBreakerConfig
import apap.execution.retry.RetryConfig
import apap.execution.structuredoutput.StructuredOutputConfig
import apap.execution.structuredoutput.StructuredOutputCorrectionBudget
import apap.execution.testsupport.FakeAdapterRegistry
import apap.execution.testsupport.TEST_REGION
import apap.execution.testsupport.testCandidate
import apap.execution.testsupport.testCanonicalRequest
import apap.execution.testsupport.testModel
import apap.execution.testsupport.testModelId
import apap.execution.testsupport.testProvider
import apap.execution.testsupport.testProviderId
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import apap.testkit.inmemory.InMemoryModelRepository
import apap.testkit.inmemory.InMemoryProviderRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** 02_システム仕様.md 2.11の表 / ADR-0011 是正リトライ。 */
class AttemptExecutorTest {
    private val providerId = testProviderId()
    private val modelId = testModelId()
    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val events = InMemoryDomainEventPublisher()
    private val ids = InMemoryIdGenerator()
    private val providerRepository = InMemoryProviderRepository().apply { save(testProvider(providerId, "plugin-a")) }
    private val modelRepository = InMemoryModelRepository().apply { save(testModel(modelId, providerId)) }
    private val rateLimiterConfig = RateLimiterConfig(defaultCapacity = 1000, defaultRefillPerSecond = 1000.0)
    private val rateLimiter = TokenBucketRateLimiter(clock, events, ids, rateLimiterConfig)

    private fun defaultCb() =
        CircuitBreaker(
            InMemoryCircuitBreakerStateStore(),
            clock,
            events,
            ids,
            CircuitBreakerConfig(),
        )

    private fun executorFor(
        adapter: MockProviderAdapter,
        retryConfig: RetryConfig = RetryConfig(),
        cb: CircuitBreaker = defaultCb(),
    ): AttemptExecutor =
        AttemptExecutor(
            providerRepository,
            modelRepository,
            FakeAdapterRegistry("plugin-a", adapter),
            cb,
            rateLimiter,
            clock,
            events,
            ids,
            retryConfig,
        )

    private fun ctx(timeoutBudget: Duration = Duration.ofSeconds(60)) =
        ExecutionContext.start(
            requestId = testCanonicalRequest().requestId,
            tenantId = testCanonicalRequest().tenantId,
            traceId = "trace",
            now = clock.now(),
            timeoutBudget = timeoutBudget,
        )

    private fun scriptedAdapter(vararg categories: AdapterErrorCategory?): MockProviderAdapter {
        val outcomes = categories.map { ScriptedOutcome(errorCategory = it) }
        val adapter = MockProviderAdapter(MockAdapterConfig(scriptedOutcomes = outcomes))
        adapter.initialize(
            AdapterConfig(
                providerId = providerId,
                endpoints = listOf(Endpoint("ep1", TEST_REGION, "https://example.internal", 100)),
                rateLimits = RateLimits(600, 100_000, 10),
                regions = setOf(TEST_REGION),
            ),
            FakeSecretAccessor,
        )
        return adapter
    }

    private object FakeSecretAccessor : SecretAccessor {
        override fun resolve(ref: CredentialRef): SecretValue = SecretValue("test-secret".toCharArray())
    }

    private suspend fun run(
        executor: AttemptExecutor,
        budget: StructuredOutputCorrectionBudget = StructuredOutputCorrectionBudget(),
        timeoutBudget: Duration = Duration.ofSeconds(60),
    ): AttemptResult =
        executor.execute(
            testCandidate(providerId, modelId),
            ProcessedPrompt(input = testCanonicalRequest().input),
            testCanonicalRequest(),
            ctx(timeoutBudget),
            budget,
        )

    @Test
    fun `TRANSIENT retries up to maxAttempts then fails`() =
        runBlocking {
            val category = AdapterErrorCategory.TRANSIENT
            val adapter = scriptedAdapter(category, category, category)
            val result = run(executorFor(adapter, RetryConfig(maxAttempts = 3, baseBackoffMs = 1)))
            assertTrue(result is AttemptResult.Failure)
            assertEquals(3, (result as AttemptResult.Failure).attempts)
        }

    @Test
    fun `TRANSIENT succeeds after one retry`() =
        runBlocking {
            val adapter = scriptedAdapter(AdapterErrorCategory.TRANSIENT)
            val result = run(executorFor(adapter, RetryConfig(baseBackoffMs = 1)))
            assertTrue(result is AttemptResult.Success)
        }

    @Test
    fun `INVALID_REQUEST does not retry`() =
        runBlocking {
            val category = AdapterErrorCategory.INVALID_REQUEST
            val result = run(executorFor(scriptedAdapter(category, category)))
            assertTrue(result is AttemptResult.Failure)
            assertEquals(1, (result as AttemptResult.Failure).attempts)
        }

    @Test
    fun `AUTH_ERROR does not retry`() =
        runBlocking {
            val category = AdapterErrorCategory.AUTH_ERROR
            val result = run(executorFor(scriptedAdapter(category, category)))
            assertEquals(1, (result as AttemptResult.Failure).attempts)
        }

    @Test
    fun `CONTENT_FILTERED does not retry`() =
        runBlocking {
            val category = AdapterErrorCategory.CONTENT_FILTERED
            val result = run(executorFor(scriptedAdapter(category, category)))
            assertEquals(1, (result as AttemptResult.Failure).attempts)
        }

    @Test
    fun `PROVIDER_UNAVAILABLE does not retry within the same candidate`() =
        runBlocking {
            val category = AdapterErrorCategory.PROVIDER_UNAVAILABLE
            val result = run(executorFor(scriptedAdapter(category, category)))
            assertEquals(1, (result as AttemptResult.Failure).attempts)
        }

    @Test
    fun `RATE_LIMITED retries with Retry-After honored`() =
        runBlocking {
            val adapter = scriptedAdapter(AdapterErrorCategory.RATE_LIMITED)
            val result = run(executorFor(adapter, RetryConfig(baseBackoffMs = 1)))
            assertTrue(result is AttemptResult.Success)
        }

    @Test
    fun `MODEL_ERROR corrects and succeeds within the correction budget`() =
        runBlocking {
            val adapter = scriptedAdapter(AdapterErrorCategory.MODEL_ERROR)
            val result =
                run(
                    executorFor(adapter, RetryConfig(baseBackoffMs = 1)),
                    budget = StructuredOutputCorrectionBudget(StructuredOutputConfig(2)),
                )
            assertTrue(result is AttemptResult.Success)
        }

    @Test
    fun `MODEL_ERROR stops once the correction budget is exhausted, even below maxAttempts`() =
        runBlocking {
            val category = AdapterErrorCategory.MODEL_ERROR
            val adapter = scriptedAdapter(category, category, category)
            val result =
                run(
                    executorFor(adapter, RetryConfig(maxAttempts = 5, baseBackoffMs = 1)),
                    budget = StructuredOutputCorrectionBudget(StructuredOutputConfig(maxCorrectionsPerRequest = 2)),
                )
            assertTrue(result is AttemptResult.Failure)
            // budget=2 corrections consumed on attempt 1 and 2's failures; attempt 3 fails again but the
            // budget is exhausted, so the loop stops at attempt 3 even though maxAttempts=5 would allow more.
            assertEquals(3, (result as AttemptResult.Failure).attempts)
        }

    @Test
    fun `returns immediate failure without attempting when the timeout budget is already exhausted`() =
        runBlocking {
            val adapter = scriptedAdapter(AdapterErrorCategory.TRANSIENT)
            // A 1-second budget that has already elapsed by the time execute() runs is functionally exhausted.
            val exhaustedCtx =
                ExecutionContext.start(
                    requestId = testCanonicalRequest().requestId,
                    tenantId = testCanonicalRequest().tenantId,
                    traceId = "trace",
                    now = clock.now().minusSeconds(2),
                    timeoutBudget = Duration.ofSeconds(1),
                )
            val result =
                executorFor(adapter).execute(
                    testCandidate(providerId, modelId),
                    ProcessedPrompt(input = testCanonicalRequest().input),
                    testCanonicalRequest(),
                    exhaustedCtx,
                    StructuredOutputCorrectionBudget(),
                )
            assertTrue(result is AttemptResult.Failure)
            assertEquals(0, (result as AttemptResult.Failure).attempts)
        }

    @Test
    fun `CB Open candidate is rejected without calling the adapter`() =
        runBlocking {
            val cb = defaultCb()
            val key = CbKey(providerId, modelId)
            repeat(10) {
                val permit = cb.tryAcquire(key, "trace")
                cb.recordFailure(permit, cbRecordable = true, traceId = "trace")
            }
            assertEquals(CbState.OPEN, cb.state(key))

            val result = run(executorFor(scriptedAdapter(), cb = cb))
            assertTrue(result is AttemptResult.Failure)
        }
}
