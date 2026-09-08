package apap.execution.attempt

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.mock.MockProviderAdapter
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.FinishReason
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.cache.ratelimit.RateLimiterConfig
import apap.cache.ratelimit.TokenBucketRateLimiter
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.ExecutionContext
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.ErrorCode
import apap.execution.adapter.out.InMemoryCircuitBreakerStateStore
import apap.execution.circuitbreaker.CircuitBreaker
import apap.execution.circuitbreaker.CircuitBreakerConfig
import apap.execution.retry.RetryConfig
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
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * FR-CAP-003 Structured Output: **何を検証違反とし、何を違反としないか**。
 *
 * `outputSchema`付きの応答は`AttemptExecutor`がスキーマ検証する。ここで判定を誤ると、
 * 直しようのない応答に対してADR-0011の是正リトライ（往復1回ぶんの課金と遅延）が走る。
 * 除外すべき2つの場合を検査で固定する。
 *
 * 1. **終了理由が上限切れ／拒否**: 定義上スキーマに従わない。検証失敗ではなくその終了理由のまま返す
 * 2. **enumの綴り差（大文字小文字）**: 制約付きデコードでは保証されず、しかも正常終了する
 */
class StructuredOutputValidationTest {
    private val providerId = testProviderId()
    private val modelId = testModelId()
    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val events = InMemoryDomainEventPublisher()
    private val ids = InMemoryIdGenerator()
    private val providerRepository = InMemoryProviderRepository().apply { save(testProvider(providerId, "plugin-a")) }
    private val modelRepository = InMemoryModelRepository().apply { save(testModel(modelId, providerId)) }
    private val rateLimiter =
        TokenBucketRateLimiter(
            clock,
            events,
            ids,
            RateLimiterConfig(defaultCapacity = 1000, defaultRefillPerSecond = 1000.0),
        )

    private val schema =
        """
        {"type":"object","required":["unit"],
         "properties":{"unit":{"type":"string","enum":["Celsius","Fahrenheit"]}}}
        """.trimIndent()

    @Test
    fun `a truncated response is reported as a length limit, not as a schema violation`(): Unit =
        runBlocking {
            // 上限で切られた本文はJSONとして閉じておらず、検証にかければ必ず落ちる。
            val result = execute(""" {"unit": "Cel """, FinishReason.LENGTH_LIMIT)

            assertTrue(
                result is AttemptResult.Success,
                "上限切れをスキーマ違反として扱っています（是正リトライが空回りします）: $result",
            )
            assertEquals(FinishReason.LENGTH_LIMIT, (result as AttemptResult.Success).response.finishReason)
        }

    @Test
    fun `a refused response keeps its content filtered reason instead of becoming a schema violation`(): Unit =
        runBlocking {
            val result = execute("I can't help with that.", FinishReason.CONTENT_FILTERED)

            assertTrue(result is AttemptResult.Success, "拒否をスキーマ違反として扱っています: $result")
            assertEquals(FinishReason.CONTENT_FILTERED, (result as AttemptResult.Success).response.finishReason)
        }

    @Test
    fun `an enum value that differs only in case is accepted`(): Unit =
        runBlocking {
            val result = execute("""{"unit":"celsius"}""", FinishReason.COMPLETED)

            assertTrue(
                result is AttemptResult.Success,
                "enumの綴り差だけで不適合としています（是正リトライを誘発します）: $result",
            )
        }

    /** 緩めるのは綴りだけ。値そのものが違えば従来どおり違反。 */
    @Test
    fun `a genuinely wrong enum value is still a schema violation`(): Unit =
        runBlocking {
            val result = execute("""{"unit":"kelvin"}""", FinishReason.COMPLETED)

            assertTrue(result is AttemptResult.Failure, "スキーマ違反が素通りしています: $result")
            assertEquals(ErrorCode.OUTPUT_SCHEMA_VIOLATION, (result as AttemptResult.Failure).error.code)
        }

    /** 正常終了した不適合応答は、これまでどおり是正リトライへ載せる。 */
    @Test
    fun `a completed response that ignores the schema is still a violation`(): Unit =
        runBlocking {
            val result = execute("not json at all", FinishReason.COMPLETED)

            assertTrue(result is AttemptResult.Failure, "検証が働いていません: $result")
            assertEquals(ErrorCode.OUTPUT_SCHEMA_VIOLATION, (result as AttemptResult.Failure).error.code)
        }

    private suspend fun execute(
        responseText: String,
        finishReason: FinishReason,
    ): AttemptResult {
        val adapter = adapterReturning(responseText, finishReason)
        val executor =
            AttemptExecutor(
                providerRepository,
                modelRepository,
                FakeAdapterRegistry("plugin-a", adapter),
                CircuitBreaker(InMemoryCircuitBreakerStateStore(), clock, events, ids, CircuitBreakerConfig()),
                rateLimiter,
                clock,
                events,
                ids,
                RetryConfig(maxAttempts = 1, baseBackoffMs = 1),
                tracer = OpenTelemetry.noop().getTracer("test"),
            )
        val request = requestWithSchema()
        return executor.execute(
            testCandidate(providerId, modelId),
            ProcessedPrompt(input = request.input),
            request,
            ExecutionContext.start(
                requestId = request.requestId,
                tenantId = request.tenantId,
                traceId = "trace",
                now = clock.now(),
                timeoutBudget = Duration.ofSeconds(60),
            ),
            StructuredOutputCorrectionBudget(),
            Span.getInvalid(),
        )
    }

    private fun requestWithSchema(): CanonicalRequest = testCanonicalRequest().copy(outputSchema = schema)

    private fun adapterReturning(
        responseText: String,
        finishReason: FinishReason,
    ): MockProviderAdapter =
        MockProviderAdapter(MockAdapterConfig(responseText = responseText, finishReason = finishReason)).apply {
            initialize(
                AdapterConfig(
                    providerId = providerId,
                    endpoints = listOf(Endpoint("ep1", TEST_REGION, "https://example.internal", 100)),
                    rateLimits = RateLimits(600, 100_000, 10),
                    regions = setOf(TEST_REGION),
                ),
                object : SecretAccessor {
                    override fun resolve(ref: CredentialRef): SecretValue = SecretValue("test-secret".toCharArray())
                },
            )
        }
}
