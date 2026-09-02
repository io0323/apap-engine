package apap.gateway

import apap.domain.model.vo.CapabilityId
import apap.gateway.json.GatewayJson
import io.ktor.client.request.get
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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 13_API設計.md 13.5のHTTPステータス運用、13.4のエラー形式、13.3のSSE形式を
 * 実際のHTTP経由で検証するE2Eテスト（adapter-mockのみ、外部依存なし）。
 */
class GatewayEndToEndTest {
    private fun ApplicationTestBuilder.installGateway(fixture: TestEngineFixture) {
        val (renderer, _) = testMetricsRenderer()
        application { apapGateway(fixture.engine, testGatewayConfig(), testTokenVerifier(), renderer) }
    }

    private val chatBody =
        """
        {"messages":[{"role":"user","content":[{"type":"text","text":"hello"}]}]}
        """.trimIndent()

    @Test
    fun `chat returns 200 with the 13-3 response shape and an X-Request-Id header`(): Unit =
        testApplication {
            val fixture = TestEngineFixture()
            runBlocking { fixture.registerActiveModel(CapabilityId("chat")) }
            installGateway(fixture)

            val response =
                client.post("/v1/chat") {
                    header(HttpHeaders.Authorization, "Bearer $VALID_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody(chatBody)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertNotNull(response.headers[REQUEST_ID_HEADER], "13.5: every response carries X-Request-Id")

            val body = GatewayJson.mapper.readTree(response.bodyAsText())
            // 13.3の応答フィールド（snake_case）。
            assertTrue(body.has("response_id"))
            assertTrue(body.has("request_id"))
            assertTrue(body.has("finish_reason"))
            assertTrue(body.has("usage"))
            assertTrue(body.path("output").has("message"))
            // CLAUDE.md不変条件3: 応答に物理Provider/Model名を出さない。
            assertFalse(body.has("resolved_provider"), "physical provider name must never be exposed")
            assertFalse(body.has("resolved_model"), "physical model name must never be exposed")
        }

    @Test
    fun `missing or invalid credentials produce a 401 problem details body`(): Unit =
        testApplication {
            val fixture = TestEngineFixture()
            installGateway(fixture)

            val noHeader =
                client.post("/v1/chat") {
                    contentType(ContentType.Application.Json)
                    setBody(chatBody)
                }
            assertEquals(HttpStatusCode.Unauthorized, noHeader.status)
            noHeader.assertProblem(expectedCode = "UNAUTHENTICATED", expectedStatus = UNAUTHORIZED)

            val badToken =
                client.post("/v1/chat") {
                    header(HttpHeaders.Authorization, "Bearer nope")
                    contentType(ContentType.Application.Json)
                    setBody(chatBody)
                }
            assertEquals(HttpStatusCode.Unauthorized, badToken.status)
            badToken.assertProblem(expectedCode = "UNAUTHENTICATED", expectedStatus = UNAUTHORIZED)
        }

    @Test
    fun `admin endpoints reject a token without the admin scope with 403`(): Unit =
        testApplication {
            val fixture = TestEngineFixture()
            installGateway(fixture)

            val response =
                client.get("/admin/v1/providers") {
                    header(HttpHeaders.Authorization, "Bearer $VALID_TOKEN")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            response.assertProblem(expectedCode = "PERMISSION_DENIED", expectedStatus = FORBIDDEN)
        }

    @Test
    fun `admin endpoints accept a token carrying the admin scope`(): Unit =
        testApplication {
            val fixture = TestEngineFixture()
            installGateway(fixture)

            val response =
                client.get("/admin/v1/providers") {
                    header(HttpHeaders.Authorization, "Bearer $VALID_ADMIN_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `endpoints defined in 13-1 but not provided return 501 naming what is missing`(): Unit =
        testApplication {
            val fixture = TestEngineFixture()
            installGateway(fixture)

            val response =
                client.post("/v1/batches") {
                    header(HttpHeaders.Authorization, "Bearer $VALID_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }

            assertEquals(HttpStatusCode.NotImplemented, response.status)
            val problem = response.assertProblem(expectedCode = "NOT_IMPLEMENTED", expectedStatus = NOT_IMPLEMENTED)
            // ADR-0027: 黙って501にせず「何が無いのか」を必ず載せる。
            assertTrue(
                problem.path("detail").asText().contains("apap-runtime"),
                "the 501 body must explain which use case is missing, not just say 'not implemented'",
            )
        }

    @Test
    fun `the endpoint catalog is served so clients can discover availability before calling`(): Unit =
        testApplication {
            val fixture = TestEngineFixture()
            installGateway(fixture)

            val response = client.get("/v1/_endpoints")

            assertEquals(HttpStatusCode.OK, response.status)
            val entries = GatewayJson.mapper.readTree(response.bodyAsText())
            assertTrue(entries.isArray && entries.size() > 0)
            assertTrue(entries.any { it.path("status").asText() == "IMPLEMENTED" })
            assertTrue(entries.any { it.path("status").asText() == "NOT_IMPLEMENTED" })
        }

    @Test
    fun `healthz and readyz are reachable without authentication`(): Unit =
        testApplication {
            val fixture = TestEngineFixture()
            installGateway(fixture)

            assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
            // readyzはDOWNなら503。いずれにせよ401にはならないことがここでの主張。
            assertTrue(client.get("/readyz").status.value in setOf(OK, SERVICE_UNAVAILABLE))
        }

    @Test
    fun `metrics is served in OpenMetrics form terminated by EOF`(): Unit =
        testApplication {
            val fixture = TestEngineFixture()
            installGateway(fixture)

            val response = client.get("/metrics")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.bodyAsText().trimEnd().endsWith("# EOF"),
                "OpenMetrics requires the payload to end with '# EOF'",
            )
        }

    @Test
    fun `streaming chat emits 13-3 SSE events with no-store and terminates`(): Unit =
        testApplication {
            val fixture = TestEngineFixture()
            runBlocking { fixture.registerActiveModel(CapabilityId("chat")) }
            installGateway(fixture)

            val response =
                client.post("/v1/chat") {
                    header(HttpHeaders.Authorization, "Bearer $VALID_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"stream":true,"messages":[{"role":"user","content":[{"type":"text","text":"hi"}]}]}""")
                }

            assertEquals(HttpStatusCode.OK, response.status, "13.5: SSE also starts with 200")
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl], "13.5: streaming is no-store")

            val text = response.bodyAsText()
            val eventNames =
                Regex("^event: (.+)$", RegexOption.MULTILINE)
                    .findAll(text)
                    .map { it.groupValues[1] }
                    .toList()
            assertTrue(eventNames.isNotEmpty(), "the stream produced no SSE events at all")
            // 13.3のイベント名以外が混ざっていないこと。
            assertTrue(
                eventNames.all { it in apap.gateway.sse.SseEventName.all },
                "unexpected SSE event names: ${eventNames.filterNot { it in apap.gateway.sse.SseEventName.all }}",
            )
            // 順序: message_startが最初、message_end（あれば）が最後。
            assertEquals(apap.gateway.sse.SseEventName.MESSAGE_START, eventNames.first())
            if (apap.gateway.sse.SseEventName.MESSAGE_END in eventNames) {
                assertEquals(apap.gateway.sse.SseEventName.MESSAGE_END, eventNames.last())
            }
        }

    private suspend fun io.ktor.client.statement.HttpResponse.assertProblem(
        expectedCode: String,
        expectedStatus: Int,
    ): com.fasterxml.jackson.databind.JsonNode {
        val problem = GatewayJson.mapper.readTree(bodyAsText())
        // 13.4: RFC 9457 Problem Details + APAP拡張。
        assertEquals(expectedCode, problem.path("code").asText())
        assertEquals(expectedStatus, problem.path("status").asInt())
        assertTrue(problem.has("type"), "RFC 9457 requires 'type'")
        assertTrue(problem.has("title"), "RFC 9457 requires 'title'")
        assertTrue(problem.has("request_id"), "13.4 requires request_id")
        assertTrue(problem.has("retryable"), "13.4 requires retryable")
        assertNotNull(headers[REQUEST_ID_HEADER], "13.5: error responses also carry X-Request-Id")
        return problem
    }

    private companion object {
        const val OK = 200
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NOT_IMPLEMENTED = 501
        const val SERVICE_UNAVAILABLE = 503
    }
}
