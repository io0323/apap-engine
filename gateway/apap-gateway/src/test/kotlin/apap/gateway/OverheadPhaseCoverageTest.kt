package apap.gateway

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.AdapterResponse
import apap.adapter.spi.ProviderAdapter
import apap.domain.model.vo.CapabilityId
import apap.testkit.inmemory.InMemoryMetricsRecorder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * ADR-0034: `apap_overhead_duration_seconds` が NFR-PRF-001 の計測区間
 * 「**Gateway受信〜Adapter送信**」を実際に覆っていることを検証する。
 *
 * ## なぜこのテストが要るのか
 *
 * P11では「メトリクスは区間を覆っていない」と報告しながら、別ハーネスの実測値で
 * 「NFR-PRF-001は満たす」と判定していた。両者は別の話だが、報告上は紛らわしい。
 * ここでは**メトリクス自身**が要件の区間を覆うことを機械検証し、
 * 「覆っているつもり」を将来にわたって防ぐ。
 *
 * ## 検証する2点
 *
 * 1. **必要なphaseが実際に記録される**こと。とくに `gateway`（HTTP層）と
 *    `dispatch`（Adapter送信直前まで）は、それぞれGatewayと`AttemptExecutor`が
 *    記録しなければどこからも記録されない。2.19が挙げる
 *    `gateway` / `prompt` / `routing` / `mapping` の4ラベルも必ず含まれること。
 * 2. **Provider呼び出し時間が overhead に混ざっていない**こと。
 *    絶対値の閾値では判定できない——初回リクエストはクラスロードとJITで1秒を超えることがあり、
 *    人工遅延との大小比較が warmup ノイズに埋もれる（実際に最初そう書いて誤検知した）。
 *    そこで**差分**で見る: Provider遅延0msと400msで同じリクエストを流し、
 *    overhead合計の増分がProvider遅延に比例しないことを確認する。混入していれば増分は
 *    そのまま400msになる。warmupの影響は両方に等しく乗るので差分では相殺される。
 */
class OverheadPhaseCoverageTest {
    @Test
    fun `overhead phases cover gateway receipt through adapter dispatch and exclude the provider call`() {
        // warmup（クラスロード・JITの影響を測定から外す）→ 遅延なし → 遅延あり、の順で流す。
        driveOnce(Duration.ZERO)
        val fast = driveOnce(Duration.ZERO)
        val slow = driveOnce(PROVIDER_LATENCY)

        val phases = fast.overheadDurations.map { it.phase }.toSet()

        // 収集経路が生きていることを先に確認する。0件に対する包含検査は
        // 「覆えていない」ではなく「測れていない」を見逃す。
        assertTrue(phases.isNotEmpty(), "overhead phaseが1件も記録されていません。計測経路が壊れています。")

        // 2.19が定める4ラベル。gatewayとdispatchはP11時点では一度も記録されていなかった。
        val required = setOf("gateway", "prompt", "routing", "mapping", "dispatch")
        val missing = required - phases
        assertTrue(
            missing.isEmpty(),
            "NFR-PRF-001の区間を覆うのに必要なphaseが記録されていません: $missing（記録されたのは $phases）",
        )

        // execution phaseはProvider呼び出しを内包するため、metricsへ記録してはいけない。
        assertTrue(
            "execution" !in phases,
            "execution phaseがoverheadへ記録されています。この区間はProvider呼び出しを内包するため、" +
                "NFR-PRF-001の付加分として数えてはいけません（ADR-0034）。",
        )

        // 差分検査: Provider遅延を増やしてもoverhead合計はほとんど増えないこと。
        val fastTotal = fast.overheadDurations.sumOf { it.seconds }
        val slowTotal = slow.overheadDurations.sumOf { it.seconds }
        val latencySeconds = PROVIDER_LATENCY.toMillis() / MILLIS_PER_SECOND
        assertTrue(
            slowTotal - fastTotal < latencySeconds * LEAK_THRESHOLD,
            "Provider遅延を${PROVIDER_LATENCY.toMillis()}ms増やしたらoverhead合計が" +
                "${"%.3f".format(slowTotal - fastTotal)}秒増えました。" +
                "Provider呼び出し時間がoverheadへ混入しています" +
                "（fast=${"%.3f".format(fastTotal)}s, slow=${"%.3f".format(slowTotal)}s）。",
        )
    }

    /** 指定の人工遅延でchatを1回流し、その1回分の記録を返す。 */
    private fun driveOnce(latency: Duration): InMemoryMetricsRecorder {
        val recorder = InMemoryMetricsRecorder()
        testApplication {
            val fixture =
                TestEngineFixture(
                    adapterConfig = MockAdapterConfig(supportedCapabilities = setOf(CapabilityId("chat"))),
                    adapterDecorator = { SlowAdapter(it, latency) },
                    metricsRecorder = recorder,
                )
            fixture.registerActiveModel(CapabilityId("chat"))
            val (renderer, _) = testMetricsRenderer()
            application { apapGateway(fixture.engine, testGatewayConfig(), testTokenVerifier(), renderer) }

            val response =
                client.post("/v1/chat") {
                    header(HttpHeaders.Authorization, "Bearer $VALID_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"messages":[{"role":"user","content":[{"type":"text","text":"hi"}]}]}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        return recorder
    }

    /** `execute`に人工遅延を入れるデコレータ。Provider時間の混入を検出するために使う。 */
    private class SlowAdapter(
        private val delegate: ProviderAdapter,
        private val latency: Duration,
    ) : ProviderAdapter by delegate {
        override suspend fun execute(request: AdapterRequest): AdapterResponse {
            delay(latency.toMillis())
            return delegate.execute(request)
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0

        /** Provider呼び出しの人工遅延。混入していればoverhead合計がこの分だけ増える。 */
        val PROVIDER_LATENCY: Duration = Duration.ofMillis(400)

        /**
         * 混入とみなす増分の割合。完全に混入していれば1.0（遅延がそのまま乗る）。
         * 実行ごとのばらつきを見込んで半分を閾値にする。
         */
        const val LEAK_THRESHOLD = 0.5
    }
}
