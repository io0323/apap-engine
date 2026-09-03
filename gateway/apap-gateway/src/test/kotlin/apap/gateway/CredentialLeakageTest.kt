package apap.gateway

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.CredentialRef
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 01_CLAUDE.md 不変条件4 / FR-SEC-001 / NFR-SEC-002:
 * Credentialの実値が**ログ・例外・メトリクスラベル・HTTP応答**のいずれにも現れないこと。
 *
 * ## なぜ静的検査ではなく実行時検査なのか
 *
 * 「`SecretValue`をログへ渡している箇所が無い」ことをソース上で確認しても、実際には
 * 例外の`cause`チェーン、`toString()`の自動展開、メトリクスのラベル値、Problem Detailsの
 * `detail`といった**間接経路**で漏れうる。本テストは実際にエンジンとHTTP層を起動し、
 * 見張り文字列（sentinel）を正規のCredential経路へ流し込んだうえで、出口を実際に読み取る。
 *
 * Audit本文は`AuditEngine`が本番配線（`ExecutionEngineComposer`）に存在しないため
 * Gateway経由では観測できない。同じsentinel検査を`apap-runtime`の`CapabilitySmokeTest`が
 * AuditRecordに対して行う（そちらはテストハーネスがAuditEngineを明示的に配線している）。
 *
 * ## 検査する出口
 *
 * 1. HTTP応答ボディ（成功・SSE・認証失敗・Provider失敗）
 * 2. HTTP応答ヘッダ（全ヘッダ値）
 * 3. `/metrics`（OpenMetrics本文。メトリクスラベルはここに現れる）
 * 4. ログ（logbackの[ListAppender]で全レベルを捕捉。例外のスタックトレースも文字列化する）
 */
class CredentialLeakageTest {
    /** Providerの実Credential値。Adapterが[SecretAccessor]経由で受け取る。 */
    private val credentialSentinel = "CANARY-CREDENTIAL-8f3a2b1c9d4e"

    /** 呼び出し側のBearerトークン。これもCredentialであり、応答やログに出てはならない。 */
    private val bearerSentinel = "CANARY-BEARER-1a2b3c4d5e6f"

    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun attachLogCapture() {
        appender = ListAppender<ILoggingEvent>().apply { start() }
        rootLogger().apply {
            level = Level.TRACE
            addAppender(appender)
        }
    }

    @AfterEach
    fun detachLogCapture() {
        rootLogger().detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `no credential value reaches responses, headers, metrics or logs on success or failure`() {
        val observed = StringBuilder()
        // 正常系と、Provider起因の失敗（例外メッセージ・スタックトレース経路）の双方を通す。
        observed.append(driveGateway(forcedError = null))
        observed.append(driveGateway(forcedError = AdapterErrorCategory.AUTH_ERROR))
        observed.append(capturedLogs())

        // 検査対象が実際に集まっていることを先に確認する。空文字列に対する「含まれない」は
        // 常に真であり、収集が壊れていても緑になる（本プロジェクトが繰り返し踏んだ形）。
        assertTrue(
            observed.length > MIN_OBSERVED_CHARS,
            "検査対象の出力が${observed.length}文字しか集まっていません。収集が壊れている可能性があります。",
        )
        assertTrue(
            observed.contains(LOG_PROBE),
            "ログが収集できていません（プローブ行が見当たらない）。この状態ではログ経路の漏洩を検出できません。",
        )
        assertTrue(
            observed.contains("request_id") || observed.contains("\"id\""),
            "応答ボディが収集できていません。この状態では応答経路の漏洩を検出できません。",
        )

        val leaks = listOf(credentialSentinel, bearerSentinel).filter { observed.contains(it) }
        assertTrue(
            leaks.isEmpty(),
            "Credentialの実値が出力へ混入しました（不変条件4）: $leaks",
        )
    }

    /**
     * sentinelを正規のCredential経路（[SecretAccessor]とAuthorizationヘッダ）から流し込み、
     * 応答・ヘッダ・`/metrics`を文字列として集める。
     */
    private fun driveGateway(forcedError: AdapterErrorCategory?): String {
        val collected = StringBuilder()
        testApplication {
            val fixture =
                TestEngineFixture(
                    adapterConfig =
                        MockAdapterConfig(
                            supportedCapabilities = setOf(CapabilityId("chat")),
                            forcedErrorCategory = forcedError,
                        ),
                    secretAccessor = sentinelSecretAccessor(),
                )
            fixture.registerActiveModel(CapabilityId("chat"))
            val (renderer, _) = testMetricsRenderer()
            application {
                apapGateway(
                    fixture.engine,
                    testGatewayConfig(),
                    FakeTokenVerifier(tenantId = TEST_TENANT, validTokens = mapOf(bearerSentinel to emptySet())),
                    renderer,
                )
            }

            collected.append(chat(stream = false, token = bearerSentinel).dump())
            collected.append(chat(stream = true, token = bearerSentinel).dump())
            // 認証失敗。提示されたトークン文字列そのものを応答へ反射しないこと。
            collected.append(chat(stream = false, token = "$bearerSentinel-invalid").dump())
            collected.append(client.get("/metrics").dump())
        }
        return collected.toString()
    }

    /** sentinelを実Credentialとして返すアクセサ。Adapterはこれを正規の経路として受け取る。 */
    private fun sentinelSecretAccessor() =
        object : SecretAccessor {
            override fun resolve(ref: CredentialRef): SecretValue = SecretValue(credentialSentinel.toCharArray())
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.chat(
        stream: Boolean,
        token: String,
    ): HttpResponse =
        client.post("/v1/chat") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"stream":$stream,"messages":[{"role":"user","content":[{"type":"text","text":"hi"}]}]}""",
            )
        }

    private suspend fun HttpResponse.dump(): String =
        buildString {
            append(status.toString()).append('\n')
            headers.forEach { name, values -> append(name).append(": ").append(values.joinToString(",")).append('\n') }
            append(bodyAsText()).append('\n')
        }

    /**
     * 捕捉したログをメッセージ・引数・例外スタックトレース込みで文字列化する。
     *
     * `ListAppender.list`は素の`ArrayList`で、同一JVMで動く他テストのNetty/Ktorスレッドが
     * 並行して追記しうる。走査中の追記で`ConcurrentModificationException`にならないよう、
     * 先にスナップショットを取ってから読む。
     */
    private fun capturedLogs(): String {
        LoggerFactory.getLogger(CredentialLeakageTest::class.java).info(LOG_PROBE)
        val snapshot = appender.list.toList()
        return buildString {
            snapshot.forEach { event ->
                append(event.loggerName).append(' ').append(event.formattedMessage).append('\n')
                event.argumentArray?.forEach { append(it).append(' ') }
                var throwable = event.throwableProxy
                while (throwable != null) {
                    append(throwable.className).append(": ").append(throwable.message).append('\n')
                    throwable.stackTraceElementProxyArray?.forEach { append(it.steAsString).append('\n') }
                    throwable = throwable.cause
                }
            }
        }
    }

    private fun rootLogger() = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger(Logger.ROOT_LOGGER_NAME)

    private companion object {
        const val LOG_PROBE = "credential leakage scan completed"

        /** 応答3本 + /metrics + ログが集まっていればこの程度は超える。 */
        const val MIN_OBSERVED_CHARS = 500
    }
}
