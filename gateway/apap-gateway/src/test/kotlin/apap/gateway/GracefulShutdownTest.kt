package apap.gateway

import apap.adapter.mock.MockAdapterConfig
import apap.domain.model.vo.CapabilityId
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * 本タスク指示9 / 11_デプロイメント図.md: グレースフルシャットダウン。
 * 「PreStopで新規受付を止め、in-flightは完遂させる」を**実際のサーバで**検証する。
 *
 * `testApplication`ではなく実際の`embeddedServer`を立てる。停止の挙動（gracePeriod中に
 * 実行中リクエストを走り切らせる）はKtorのエンジン実装そのものなので、
 * テスト用ハーネスでは検証にならない。
 */
class GracefulShutdownTest {
    /**
     * 対照実験。停止を挟まなければ同じリクエストが200で返ることを先に確認する。
     * これが通らないなら、次のテストの失敗は「停止の挙動」ではなく足場の問題である
     * （切り分けができないまま実装を疑わないための対照）。
     */
    @Test
    fun `control - the same request succeeds when the server is not stopped`() {
        val port = freePort()
        val fixture = inFlightFixture()
        val server = startServer(port, fixture)
        try {
            runBlocking {
                val client = HttpClient()
                assertEquals(HttpStatusCode.OK, client.chat(port).status)
                client.close()
            }
        } finally {
            server.stop(0, 0)
            fixture.engine.close()
        }
    }

    @Test
    fun `an in-flight request completes while the server is shutting down`() {
        val port = freePort()
        val fixture = inFlightFixture()
        val lifecycle = GatewayLifecycle()
        val server = startServer(port, fixture, lifecycle)

        try {
            runBlocking {
                val client = HttpClient()
                val scope = CoroutineScope(Dispatchers.Default)

                val inFlight = scope.async { client.chat(port) }
                // 「実行中である」ことを推測しない: Adapterが実際に呼ばれるまで待つ。
                assertTrue(
                    fixture.adapterEntered.await(ENTER_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the request never reached the adapter, so it was never actually in flight",
                )

                // 本番と同じ停止手順を呼ぶ（server.stopを直接呼ぶのではなく）。
                // server.stopだけではサスペンド中のリクエストが切られることを実測しており、
                // shutdownGatewayはin-flightが捌けるのを待ってからstopする。
                val stopped = scope.async { shutdownGateway(server, fixture.engine, testGatewayConfig(), lifecycle) }

                val response = inFlight.await()
                assertEquals(
                    HttpStatusCode.OK,
                    response.status,
                    "a request already in flight must run to completion during shutdown, not be aborted",
                )
                stopped.await()
                assertEquals(0, lifecycle.inFlightCount, "the drain must finish with nothing in flight")
                client.close()
            }
        } finally {
            server.stop(0, 0)
            fixture.engine.close()
        }
    }

    @Test
    fun `closing the engine after the server stops rejects further work`() {
        val fixture = TestEngineFixture()
        // Main.startGateway の停止順序（server.stop -> engine.close）をなぞる。
        // engine.close()を先に呼ぶと、まだ処理中のHTTPリクエストが「新規」として弾かれ、
        // in-flight完遂の要件を満たせなくなる——順序が意味を持つことをここで固定する。
        fixture.engine.close()

        val rejected =
            runCatching {
                runBlocking {
                    fixture.engine.execute(
                        apap.api.ApapRequest(
                            tenantId = TEST_TENANT,
                            principal = "p",
                            capabilityId = CapabilityId("chat"),
                            input =
                                listOf(
                                    apap.domain.model.vo.ContentPart
                                        .Text("x"),
                                ),
                        ),
                    )
                }
            }
        assertTrue(
            rejected.exceptionOrNull() is IllegalStateException,
            "after close() the engine must reject new work: ${rejected.exceptionOrNull()}",
        )
    }

    private fun inFlightFixture(): TestEngineFixture {
        val fixture =
            TestEngineFixture(
                MockAdapterConfig(
                    supportedCapabilities = setOf(CapabilityId("chat")),
                    // 停止要求より長く滞留させ、「停止中に実行中だった」状況を確実に作る。
                    extraDelayMillis = IN_FLIGHT_HOLD_MILLIS,
                ),
            )
        runBlocking { fixture.registerActiveModel(CapabilityId("chat")) }
        return fixture
    }

    private fun startServer(
        port: Int,
        fixture: TestEngineFixture,
        lifecycle: GatewayLifecycle = GatewayLifecycle(),
    ): EmbeddedServer<*, *> {
        val (renderer, _) = testMetricsRenderer()
        val server =
            embeddedServer(Netty, port = port) {
                apapGateway(fixture.engine, testGatewayConfig(), testTokenVerifier(), renderer, lifecycle = lifecycle)
            }
        server.start(wait = false)
        return server
    }

    private suspend fun HttpClient.chat(port: Int): HttpResponse =
        post("http://127.0.0.1:$port/v1/chat") {
            header(HttpHeaders.Authorization, "Bearer $VALID_TOKEN")
            contentType(ContentType.Application.Json)
            setBody("""{"messages":[{"role":"user","content":[{"type":"text","text":"hello"}]}]}""")
        }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val IN_FLIGHT_HOLD_MILLIS = 2000L
        const val ENTER_TIMEOUT_SECONDS = 10L
        const val GRACE_PERIOD_MILLIS = 5000L
        const val SHUTDOWN_TIMEOUT_MILLIS = 10_000L
    }
}
