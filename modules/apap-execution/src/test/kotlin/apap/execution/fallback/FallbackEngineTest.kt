package apap.execution.fallback

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.mock.MockProviderAdapter
import apap.adapter.mock.ScriptedOutcome
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.cache.ratelimit.RateLimiterConfig
import apap.cache.ratelimit.TokenBucketRateLimiter
import apap.context.ContextManager
import apap.domain.model.conversation.Conversation
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.CbState
import apap.domain.model.execution.ExecutionContext
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.CbKey
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.service.conversation.AssembledContext
import apap.domain.service.routing.FallbackChain
import apap.execution.adapter.out.InMemoryCircuitBreakerStateStore
import apap.execution.attempt.AttemptExecutor
import apap.execution.attempt.AttemptResult
import apap.execution.circuitbreaker.CircuitBreaker
import apap.execution.circuitbreaker.CircuitBreakerConfig
import apap.execution.retry.RetryConfig
import apap.execution.structuredoutput.StructuredOutputConfig
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

/** 02_システム仕様.md 2.12 / 05_シーケンス設計.md 5.6 / 10_アクティビティ図.md 10.2。 */
class FallbackEngineTest {
    private val providerA = testProviderId("2")
    private val modelA = testModelId("3")
    private val providerB = testProviderId("4")
    private val modelB = testModelId("5")

    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val events = InMemoryDomainEventPublisher()
    private val ids = InMemoryIdGenerator()
    private val providerRepository =
        InMemoryProviderRepository().apply {
            save(testProvider(providerA, "plugin-a"))
            save(testProvider(providerB, "plugin-b"))
        }
    private val modelRepository =
        InMemoryModelRepository().apply {
            save(testModel(modelA, providerA))
            save(testModel(modelB, providerB))
        }
    private val rateLimiterConfig = RateLimiterConfig(defaultCapacity = 1000, defaultRefillPerSecond = 1000.0)
    private val rateLimiter = TokenBucketRateLimiter(clock, events, ids, rateLimiterConfig)

    /**
     * このテストは[ContextManager.refit]（実際に`FallbackEngine`が呼ぶ口）のみを対象とし、
     * P5当時のPassthrough実装と同じ「無変更で返す」挙動のフェイクとする。`build`は本テストの
     * 対象外（未使用）のため呼ばれたら失敗させる。
     */
    private val contextManager =
        object : ContextManager {
            override fun build(
                request: CanonicalRequest,
                systemPrompt: List<ContentPart>,
                conversation: Conversation?,
                modelId: ModelId,
            ): AssembledContext = error("not used by this test")

            override fun refit(
                prompt: ProcessedPrompt,
                modelId: ModelId,
            ): ProcessedPrompt = prompt
        }

    private fun initialized(
        providerId: ProviderId,
        config: MockAdapterConfig,
    ): MockProviderAdapter {
        val adapter = MockProviderAdapter(config)
        adapter.initialize(
            AdapterConfig(
                providerId,
                listOf(Endpoint("ep1", TEST_REGION, "https://x.internal", 100)),
                RateLimits(600, 100_000, 10),
                setOf(TEST_REGION),
            ),
            object : SecretAccessor {
                override fun resolve(ref: CredentialRef): SecretValue = SecretValue("secret".toCharArray())
            },
        )
        return adapter
    }

    private fun defaultCb() =
        CircuitBreaker(
            InMemoryCircuitBreakerStateStore(),
            clock,
            events,
            ids,
            CircuitBreakerConfig(),
        )

    private fun engine(
        adapterA: MockProviderAdapter,
        adapterB: MockProviderAdapter,
        cb: CircuitBreaker = defaultCb(),
        retryConfig: RetryConfig = RetryConfig(baseBackoffMs = 1),
        structuredOutputConfig: StructuredOutputConfig = StructuredOutputConfig(),
    ): FallbackEngine {
        val registry = FakeAdapterRegistry(mapOf("plugin-a" to adapterA, "plugin-b" to adapterB))
        val attemptExecutor =
            AttemptExecutor(
                providerRepository,
                modelRepository,
                registry,
                cb,
                rateLimiter,
                clock,
                events,
                ids,
                retryConfig,
            )
        return FallbackEngine(attemptExecutor, cb, contextManager, clock, events, ids, structuredOutputConfig)
    }

    private fun chain(p50LatencyMsB: Double = 50.0) =
        FallbackChain(
            listOf(
                testCandidate(providerA, modelA, p50LatencyMs = 50.0),
                testCandidate(providerB, modelB, p50LatencyMs = p50LatencyMsB),
            ),
        )

    private fun ctx(timeoutBudget: Duration = Duration.ofSeconds(60)) =
        ExecutionContext.start(
            testCanonicalRequest().requestId,
            testCanonicalRequest().tenantId,
            "trace",
            clock.now(),
            timeoutBudget,
        )

    private suspend fun runChain(
        fallbackEngine: FallbackEngine,
        fallbackChain: FallbackChain = chain(),
        timeoutBudget: Duration = Duration.ofSeconds(60),
    ): AttemptResult =
        fallbackEngine.executeWithChain(
            fallbackChain,
            ProcessedPrompt(testCanonicalRequest().input),
            testCanonicalRequest(),
            ctx(timeoutBudget),
        )

    @Test
    fun `first candidate fails, second succeeds`() =
        runBlocking {
            val config = MockAdapterConfig(forcedErrorCategory = AdapterErrorCategory.PROVIDER_UNAVAILABLE)
            val adapterA = initialized(providerA, config)
            val adapterB = initialized(providerB, MockAdapterConfig())
            val result = runChain(engine(adapterA, adapterB))
            assertTrue(result is AttemptResult.Success)
            assertEquals(providerB, (result as AttemptResult.Success).candidate.providerId)
        }

    @Test
    fun `all candidates fail returns the last error with correct attempts and fallbacks`() =
        runBlocking {
            val category = AdapterErrorCategory.PROVIDER_UNAVAILABLE
            val adapterA = initialized(providerA, MockAdapterConfig(forcedErrorCategory = category))
            val adapterB = initialized(providerB, MockAdapterConfig(forcedErrorCategory = category))
            val result = runChain(engine(adapterA, adapterB))
            assertTrue(result is AttemptResult.Failure)
            val failure = result as AttemptResult.Failure
            // 1 attempt per candidate (PROVIDER_UNAVAILABLE is not retryable).
            assertEquals(2, failure.attempts)
            assertEquals(1, failure.fallbacks)
        }

    @Test
    fun `stops without falling back when the error is not fallbackable`() =
        runBlocking {
            val config = MockAdapterConfig(forcedErrorCategory = AdapterErrorCategory.INVALID_REQUEST)
            val adapterA = initialized(providerA, config)
            val adapterB = initialized(providerB, MockAdapterConfig())
            val result = runChain(engine(adapterA, adapterB))
            assertTrue(result is AttemptResult.Failure)
            assertEquals(0, (result as AttemptResult.Failure).fallbacks)
        }

    @Test
    fun `CB Open candidate is skipped entirely`() =
        runBlocking {
            val cb = defaultCb()
            val keyA = CbKey(providerA, modelA)
            repeat(10) {
                val permit = cb.tryAcquire(keyA, "trace")
                cb.recordFailure(permit, cbRecordable = true, traceId = "trace")
            }
            assertEquals(CbState.OPEN, cb.state(keyA))

            val adapterA = initialized(providerA, MockAdapterConfig())
            val adapterB = initialized(providerB, MockAdapterConfig())
            val result = runChain(engine(adapterA, adapterB, cb = cb))
            assertTrue(result is AttemptResult.Success)
            assertEquals(providerB, (result as AttemptResult.Success).candidate.providerId)
        }

    @Test
    fun `stops when remaining budget cannot cover the next candidate's p50 latency`() =
        runBlocking {
            val config = MockAdapterConfig(forcedErrorCategory = AdapterErrorCategory.PROVIDER_UNAVAILABLE)
            val adapterA = initialized(providerA, config)
            val adapterB = initialized(providerB, MockAdapterConfig())
            // Budget is only 500ms; the next candidate's p50 latency (100_000ms) exceeds it.
            val result =
                runChain(
                    engine(adapterA, adapterB),
                    fallbackChain = chain(p50LatencyMsB = 100_000.0),
                    timeoutBudget = Duration.ofMillis(500),
                )
            assertTrue(result is AttemptResult.Failure)
            assertEquals(0, (result as AttemptResult.Failure).fallbacks)
        }

    @Test
    fun `structured output correction budget is not reset across a fallback to a different candidate`() =
        runBlocking {
            // Candidate A always returns MODEL_ERROR (consumes both correction slots across its own retries).
            val modelErrorOutcomes = List(5) { ScriptedOutcome(AdapterErrorCategory.MODEL_ERROR) }
            val adapterA = initialized(providerA, MockAdapterConfig(scriptedOutcomes = modelErrorOutcomes))
            // Candidate B would also violate the schema, but by the time we reach it the correction
            // budget (2, global) must already be exhausted by A's failures, so B gets only 1 attempt.
            val configB = MockAdapterConfig(forcedErrorCategory = AdapterErrorCategory.MODEL_ERROR)
            val adapterB = initialized(providerB, configB)

            val result =
                runChain(
                    engine(
                        adapterA,
                        adapterB,
                        retryConfig = RetryConfig(maxAttempts = 5, baseBackoffMs = 1),
                        structuredOutputConfig = StructuredOutputConfig(maxCorrectionsPerRequest = 2),
                    ),
                )

            assertTrue(result is AttemptResult.Failure)
            val failure = result as AttemptResult.Failure
            // A: attempts 1,2,3 (2 corrections consumed, budget exhausted on 3rd MODEL_ERROR) = 3 attempts.
            // B: budget already exhausted -> MODEL_ERROR is retryable in principle but correction budget
            //    gating means the loop stops after the very first attempt = 1 attempt.
            assertEquals(4, failure.attempts)
        }
}
