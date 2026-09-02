package apap.gateway

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.mock.ScriptedOutcome
import apap.adapter.spi.AdapterErrorCategory
import apap.domain.model.vo.CapabilityId
import apap.gateway.json.GatewayJson
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 13_API設計.md 13.5「429/503には`Retry-After`」/ 13.4の`retry_after_ms`、
 * および13章共通事項の`Idempotency-Key`。
 */
class RateLimitAndIdempotencyTest {
    private val chatBody =
        """{"messages":[{"role":"user","content":[{"type":"text","text":"hello"}]}]}"""

    private fun ApplicationTestBuilder.installGateway(fixture: TestEngineFixture) {
        val (renderer, _) = testMetricsRenderer()
        application { apapGateway(fixture.engine, testGatewayConfig(), testTokenVerifier(), renderer) }
    }

    @Test
    fun `a rate limited request answers 429 carrying both retry_after_ms and the Retry-After header`(): Unit =
        testApplication {
            // Providerが常にRATE_LIMITEDで拒否する台本。Retry-Afterヘッダ相当の猶予も返させる。
            val fixture =
                TestEngineFixture(
                    MockAdapterConfig(
                        supportedCapabilities = setOf(CapabilityId("chat")),
                        scriptedOutcomes =
                            List(EXHAUST_ATTEMPTS) {
                                ScriptedOutcome(
                                    errorCategory = AdapterErrorCategory.RATE_LIMITED,
                                    retryAfter = Duration.ofMillis(RETRY_AFTER_MS),
                                )
                            },
                    ),
                )
            runBlocking { fixture.registerActiveModel(CapabilityId("chat")) }
            installGateway(fixture)

            val response =
                client.post("/v1/chat") {
                    header(HttpHeaders.Authorization, "Bearer $VALID_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody(chatBody)
                }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)

            val problem = GatewayJson.mapper.readTree(response.bodyAsText())
            assertEquals("RATE_LIMIT_EXCEEDED", problem.path("code").asText())
            assertTrue(problem.path("retryable").asBoolean(), "13.4: RATE_LIMIT_EXCEEDED is retryable")
            assertEquals(
                RETRY_AFTER_MS,
                problem.path("retry_after_ms").asLong(),
                "13.4 requires retry_after_ms on 429 so clients know how long to wait",
            )

            // 13.5: 429には Retry-After（RFC 9110の秒単位整数）。
            val retryAfter = response.headers[HttpHeaders.RetryAfter]
            assertNotNull(retryAfter, "13.5 requires a Retry-After header on 429")
            assertEquals(
                EXPECTED_RETRY_AFTER_SECONDS,
                retryAfter!!.toLong(),
                "retry_after_ms must be rounded up to whole seconds for the header",
            )
        }

    @Test
    fun `a second request reusing an in-flight Idempotency-Key gets 409, not a second execution`(): Unit =
        testApplication {
            // 1件目が実行中の間に2件目を送るため、Adapterに滞留させる。
            val fixture =
                TestEngineFixture(
                    MockAdapterConfig(
                        supportedCapabilities = setOf(CapabilityId("chat")),
                        extraDelayMillis = IN_FLIGHT_HOLD_MILLIS,
                    ),
                )
            runBlocking { fixture.registerActiveModel(CapabilityId("chat")) }
            installGateway(fixture)

            val key = "idem-0001"
            val scope = CoroutineScope(Dispatchers.Default)
            val first =
                scope.async {
                    client.post("/v1/chat") {
                        header(HttpHeaders.Authorization, "Bearer $VALID_TOKEN")
                        header(IDEMPOTENCY_KEY_HEADER, key)
                        contentType(ContentType.Application.Json)
                        setBody(chatBody)
                    }
                }
            // 1件目がAdapter内で滞留している間に2件目を送る。
            delay(CONCURRENT_WINDOW_MILLIS)
            val second =
                client.post("/v1/chat") {
                    header(HttpHeaders.Authorization, "Bearer $VALID_TOKEN")
                    header(IDEMPOTENCY_KEY_HEADER, key)
                    contentType(ContentType.Application.Json)
                    setBody(chatBody)
                }

            assertEquals(
                HttpStatusCode.Conflict,
                second.status,
                "13.4 CONFLICT: the same Idempotency-Key must not be executed concurrently a second time",
            )
            val problem = GatewayJson.mapper.readTree(second.bodyAsText())
            assertEquals("CONFLICT", problem.path("code").asText())

            // 1件目は普通に完遂する（冪等ガードは後発だけを弾く）。
            assertEquals(HttpStatusCode.OK, first.await().status)
        }

    private companion object {
        /** Retryの既定は最大3試行 + Fallback。台本を十分な数用意して必ず失敗で終わらせる。 */
        const val EXHAUST_ATTEMPTS = 12
        const val RETRY_AFTER_MS = 2000L
        const val EXPECTED_RETRY_AFTER_SECONDS = 2L
        const val IN_FLIGHT_HOLD_MILLIS = 1500L
        const val CONCURRENT_WINDOW_MILLIS = 300L
    }
}
