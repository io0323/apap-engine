package apap.execution.attempt

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.mock.MockProviderAdapter
import apap.adapter.mock.ScriptedOutcome
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.cache.ratelimit.RateLimitScope
import apap.cache.ratelimit.RateLimiter
import apap.cache.ratelimit.RateLimiterConfig
import apap.cache.ratelimit.TokenBucketRateLimiter
import apap.domain.event.RateLimitExceeded
import apap.domain.model.execution.CbState
import apap.domain.model.execution.ExecutionContext
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.CbKey
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.ErrorCode
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
import apap.execution.testsupport.testTenantId
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import apap.testkit.inmemory.InMemoryModelRepository
import apap.testkit.inmemory.InMemoryProviderRepository
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.StatusData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
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
        rateLimiter: RateLimiter = this.rateLimiter,
        tracer: Tracer = OpenTelemetry.noop().getTracer("test"),
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
            tracer = tracer,
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
            Span.getInvalid(),
        )

    @Test
    fun `TRANSIENT retries up to maxAttempts then fails`(): Unit =
        runBlocking {
            val category = AdapterErrorCategory.TRANSIENT
            val adapter = scriptedAdapter(category, category, category)
            val result = run(executorFor(adapter, RetryConfig(maxAttempts = 3, baseBackoffMs = 1)))
            assertTrue(result is AttemptResult.Failure)
            assertEquals(3, (result as AttemptResult.Failure).attempts)
        }

    @Test
    fun `TRANSIENT succeeds after one retry`(): Unit =
        runBlocking {
            val adapter = scriptedAdapter(AdapterErrorCategory.TRANSIENT)
            val result = run(executorFor(adapter, RetryConfig(baseBackoffMs = 1)))
            assertTrue(result is AttemptResult.Success)
        }

    /**
     * 02_システム仕様.md 2.19 Span構成`attempt[n]`。実OpenTelemetry SDK（[InMemorySpanExporter]、
     * テスト専用）で、リトライ毎に別Spanが作られ、失敗はERROR・成功はOKになることを検証する。
     */
    @Test
    fun `each retry attempt is exported as its own Span with the correct status`(): Unit =
        runBlocking {
            val spanExporter = InMemorySpanExporter.create()
            val tracerProvider =
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)).build()
            val tracer =
                OpenTelemetrySdk
                    .builder()
                    .setTracerProvider(tracerProvider)
                    .build()
                    .getTracer("test")
            val adapter = scriptedAdapter(AdapterErrorCategory.TRANSIENT)
            val executor = executorFor(adapter, RetryConfig(baseBackoffMs = 1), tracer = tracer)
            val parentSpan = tracer.spanBuilder("parent").startSpan()

            val result =
                executor.execute(
                    testCandidate(providerId, modelId),
                    ProcessedPrompt(input = testCanonicalRequest().input),
                    testCanonicalRequest(),
                    ctx(),
                    StructuredOutputCorrectionBudget(),
                    parentSpan,
                )
            parentSpan.end()

            assertTrue(result is AttemptResult.Success)
            val spans = spanExporter.finishedSpanItems.filter { it.name.startsWith("attempt[") }
            assertEquals(listOf("attempt[1]", "attempt[2]"), spans.map { it.name }.sorted())
            val attempt1 = spans.single { it.name == "attempt[1]" }
            val attempt2 = spans.single { it.name == "attempt[2]" }
            assertEquals(StatusData.error().statusCode, attempt1.status.statusCode)
            assertEquals(StatusData.ok().statusCode, attempt2.status.statusCode)
            assertEquals(parentSpan.spanContext.spanId, attempt1.parentSpanId)
            assertEquals(providerId.value, attempt2.attributes.get(AttributeKey.stringKey("provider")))
            assertEquals(2L, attempt2.attributes.get(AttributeKey.longKey("attempt")))
        }

    @Test
    fun `INVALID_REQUEST does not retry`(): Unit =
        runBlocking {
            val category = AdapterErrorCategory.INVALID_REQUEST
            val result = run(executorFor(scriptedAdapter(category, category)))
            assertTrue(result is AttemptResult.Failure)
            assertEquals(1, (result as AttemptResult.Failure).attempts)
        }

    @Test
    fun `AUTH_ERROR does not retry`(): Unit =
        runBlocking {
            val category = AdapterErrorCategory.AUTH_ERROR
            val result = run(executorFor(scriptedAdapter(category, category)))
            assertEquals(1, (result as AttemptResult.Failure).attempts)
        }

    @Test
    fun `CONTENT_FILTERED does not retry`(): Unit =
        runBlocking {
            val category = AdapterErrorCategory.CONTENT_FILTERED
            val result = run(executorFor(scriptedAdapter(category, category)))
            assertEquals(1, (result as AttemptResult.Failure).attempts)
        }

    @Test
    fun `PROVIDER_UNAVAILABLE does not retry within the same candidate`(): Unit =
        runBlocking {
            val category = AdapterErrorCategory.PROVIDER_UNAVAILABLE
            val result = run(executorFor(scriptedAdapter(category, category)))
            assertEquals(1, (result as AttemptResult.Failure).attempts)
        }

    @Test
    fun `RATE_LIMITED retries with Retry-After honored`(): Unit =
        runBlocking {
            val adapter = scriptedAdapter(AdapterErrorCategory.RATE_LIMITED)
            val result = run(executorFor(adapter, RetryConfig(baseBackoffMs = 1)))
            assertTrue(result is AttemptResult.Success)
        }

    @Test
    fun `MODEL_ERROR corrects and succeeds within the correction budget`(): Unit =
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
    fun `MODEL_ERROR stops once the correction budget is exhausted, even below maxAttempts`(): Unit =
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
    fun `returns immediate failure without attempting when the timeout budget is already exhausted`(): Unit =
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
                    Span.getInvalid(),
                )
            assertTrue(result is AttemptResult.Failure)
            assertEquals(0, (result as AttemptResult.Failure).attempts)
        }

    @Test
    fun `CB Open candidate is rejected without calling the adapter`(): Unit =
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

    /**
     * 13.4のRATE_LIMIT_EXCEEDED（429, retryable=true）と14.3のRateLimitExceededイベントは
     * ローカルRateLimiterのacquire()がAcquireResult.Rejectedを返す経路でのみ発火する
     * （AcquireResultへの型変更で「利用側の契約」が壊れていないことの回帰テスト）。
     */
    @Test
    fun `local RateLimiter rejection maps to RATE_LIMIT_EXCEEDED and publishes RateLimitExceeded`(): Unit =
        runBlocking {
            val restrictiveLimiter =
                TokenBucketRateLimiter(
                    clock,
                    events,
                    ids,
                    RateLimiterConfig(defaultCapacity = 1, defaultRefillPerSecond = 0.001),
                )
            // Exhaust the tenant-scope bucket's single token before AttemptExecutor's own acquire() call,
            // so the tenant-scope acquire() rejects immediately (deficit far exceeds the bounded maxWait).
            restrictiveLimiter.tryAcquire(RateLimitScope.TenantScope(testTenantId()))

            val adapter = scriptedAdapter(AdapterErrorCategory.TRANSIENT)
            val result = run(executorFor(adapter, rateLimiter = restrictiveLimiter))

            assertTrue(result is AttemptResult.Failure)
            val error = (result as AttemptResult.Failure).error
            assertEquals(ErrorCode.RATE_LIMIT_EXCEEDED, error.code)
            assertTrue(error.retryable)
            assertTrue(events.publishedEvents.any { it is RateLimitExceeded })
        }
}
