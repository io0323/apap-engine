package apap.gateway

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.AdapterResponse
import apap.adapter.spi.ProviderAdapter
import apap.domain.model.vo.CapabilityId
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

/**
 * NFR-PRF-001/002/003 の実測。**`verify.sh`では実行しない**（負荷測定はマシン状態に強く依存し、
 * 通常のビルドで合否を判定すべきものではないため）。実行方法:
 *
 * ```
 * ./gradlew :gateway:apap-gateway:test --tests '*PerformanceBenchmark*' -Dapap.benchmark=true
 * ```
 *
 * ## 測定方法と、その限界
 *
 * - **NFR-PRF-001「APAP付加レイテンシ（Gateway受信〜Adapter送信）」**:
 *   Gatewayの受信時刻はテスト専用プラグインが`CallSetup`で採取し、Adapter到達時刻は
 *   [TimestampingAdapter]が`execute`入口で採取する。両者の差がAPAPの付加分。
 *   逐次実行（同時に1リクエストのみ）なので[AtomicLong]1本で相関が取れる。
 *   `apap_overhead_duration_seconds`は**この区間を覆っていない**（フェーズは
 *   `ExecutionEngine.execute`の内側にしか無く、Gateway層の認証・JSON解析・
 *   Idempotency・DTO変換は計測対象外）ため、メトリクスではなくここで直接計測する。
 * - **NFR-PRF-002「Streaming初回チャンク付加遅延」**: 受信時刻から、クライアントが
 *   最初の`event: content_delta`行を読み終えるまで。Adapterは遅延ゼロで即座に
 *   チャンクを出すので、この差がAPAP+HTTPの付加分の上限になる。
 * - **NFR-PRF-003「スループット」**: 固定の並列度で一定時間リクエストを流し、
 *   成功応答数/秒を数える。
 *
 * **限界（報告時に必ず併記すること）**:
 * 1. 単一マシン・単一JVM上でクライアントとサーバが同居しており、両者がCPUを奪い合う。
 *    実測スループットは分離環境より低く出る。
 * 2. loopback HTTPのラウンドトリップが「Gateway受信」時刻の採取前に含まれない一方、
 *    クライアント側の送信処理はJVM内で競合する。
 * 3. Provider呼び出しは[MockAdapterConfig]（遅延ゼロ）であり、実Providerの
 *    I/O待ちによる並行性の効果は再現していない。
 * 4. 単一値ではなく分布（p50/p90/p99/max）を出力する。GC等の影響で最大値は跳ねる。
 */
@EnabledIfSystemProperty(named = "apap.benchmark", matches = "true")
class PerformanceBenchmark {
    /** Gatewayがリクエストを受信した時刻（nanoTime）。逐次実行前提で1本だけ持つ。 */
    private val receivedAtNanos = AtomicLong(0)

    /** Adapterの`execute`へ到達した時刻（nanoTime）。 */
    private val adapterEnteredAtNanos = AtomicLong(0)

    @Test
    fun `NFR-PRF-001 added latency from gateway receipt to adapter dispatch`() {
        withGateway { port, client ->
            repeat(WARMUP_REQUESTS) { runBlocking { client.chat(port, stream = false) } }

            val samples = LongArray(MEASURED_REQUESTS)
            repeat(MEASURED_REQUESTS) { i ->
                adapterEnteredAtNanos.set(0)
                runBlocking { client.chat(port, stream = false) }
                samples[i] = adapterEnteredAtNanos.get() - receivedAtNanos.get()
            }
            report("NFR-PRF-001 APAP付加レイテンシ（Gateway受信→Adapter送信）", samples)
        }
    }

    @Test
    fun `NFR-PRF-002 added latency to the first streaming chunk`() {
        withGateway { port, client ->
            repeat(WARMUP_STREAM_REQUESTS) { runBlocking { client.firstChunkNanos(port) } }

            val samples = LongArray(MEASURED_STREAM_REQUESTS)
            repeat(MEASURED_STREAM_REQUESTS) { i -> samples[i] = runBlocking { client.firstChunkNanos(port) } }
            report("NFR-PRF-002 Streaming初回チャンク付加遅延（Gateway受信→初回content_delta）", samples)
        }
    }

    @Test
    fun `NFR-PRF-003 throughput with the mock adapter`() {
        withGateway { port, client ->
            repeat(WARMUP_REQUESTS) { runBlocking { client.chat(port, stream = false) } }

            val (count, rps) = drive(client, port, THROUGHPUT_SECONDS)
            println("NFR-PRF-003 スループット: ${format(rps)} req/s (成功${count}件, 並列度=$CONCURRENCY)")
        }
    }

    /**
     * 出荷時の既定構成（`RateLimiterConfig()`）でのスループット。
     *
     * 他の計測はレート制限を外して**APAP自身の処理コスト**を測るが、この1本だけは
     * 既定値のままで測る。`RateLimiter.configure(...)`が本番配線から呼ばれておらず
     * Providerの`rateLimits`が反映されないため（P11-F10）、埋込ホストが
     * `rateLimiterConfig`を上書きしない限りこれが実際に出る値だからである。
     */
    @Test
    fun `NFR-PRF-003 throughput with the shipped default rate limiter config`() {
        withGateway(
            rateLimitCapacity = SHIPPED_DEFAULT_CAPACITY,
            rateLimitRefillPerSecond = SHIPPED_DEFAULT_REFILL,
        ) { port, client ->
            val succeeded = drive(client, port, THROUGHPUT_SECONDS)
            println("NFR-PRF-003（既定構成）: ${format(succeeded.second)} req/s (成功${succeeded.first}件)")
        }
    }

    /** 分布として出力する。単一の平均値は測定として意味を持たない。 */
    private fun report(
        label: String,
        samplesNanos: LongArray,
    ) {
        val sorted = samplesNanos.sortedArray()

        fun pct(p: Double): Double {
            val index = ((sorted.size - 1) * p).roundToLong().toInt()
            return sorted[index].toDouble() / NANOS_PER_MILLI
        }
        println(
            "$label: n=${sorted.size} " +
                "p50=${"%.3f".format(pct(P50))}ms " +
                "p90=${"%.3f".format(pct(P90))}ms " +
                "p99=${"%.3f".format(pct(P99))}ms " +
                "max=${"%.3f".format(sorted.last().toDouble() / NANOS_PER_MILLI)}ms " +
                "min=${"%.3f".format(sorted.first().toDouble() / NANOS_PER_MILLI)}ms",
        )
    }

    private fun withGateway(
        rateLimitCapacity: Int = UNTHROTTLED_CAPACITY,
        rateLimitRefillPerSecond: Double = UNTHROTTLED_REFILL,
        block: (Int, HttpClient) -> Unit,
    ) {
        val port = freePort()
        val fixture =
            TestEngineFixture(
                adapterConfig = MockAdapterConfig(supportedCapabilities = setOf(CapabilityId("chat"))),
                adapterDecorator = { TimestampingAdapter(it, adapterEnteredAtNanos) },
                rateLimitCapacity = rateLimitCapacity,
                rateLimitRefillPerSecond = rateLimitRefillPerSecond,
            )
        runBlocking { fixture.registerActiveModel(CapabilityId("chat")) }
        val (renderer, _) = testMetricsRenderer()
        val server =
            embeddedServer(Netty, port = port) {
                intercept(io.ktor.server.application.ApplicationCallPipeline.Setup) {
                    receivedAtNanos.set(System.nanoTime())
                }
                apapGateway(fixture.engine, testGatewayConfig(), testTokenVerifier(), renderer)
            }
        server.start(wait = false)
        val client = HttpClient()
        try {
            block(port, client)
        } finally {
            client.close()
            server.stopQuietly()
            fixture.engine.close()
        }
    }

    /** [seconds]秒間、[CONCURRENCY]並列でリクエストを流し、(成功件数, req/s) を返す。 */
    private fun drive(
        client: HttpClient,
        port: Int,
        seconds: Long,
    ): Pair<Int, Double> {
        val succeeded = AtomicInteger(0)
        val startedAt = System.nanoTime()
        val deadline = startedAt + seconds * NANOS_PER_SECOND
        runBlocking {
            coroutineScope {
                (1..CONCURRENCY)
                    .map {
                        async(Dispatchers.IO) {
                            while (System.nanoTime() < deadline) {
                                if (client.chat(port, stream = false).status == HttpStatusCode.OK) {
                                    succeeded.incrementAndGet()
                                }
                            }
                        }
                    }.awaitAll()
            }
        }
        val elapsed = (System.nanoTime() - startedAt).toDouble() / NANOS_PER_SECOND
        return succeeded.get() to succeeded.get() / elapsed
    }

    private fun format(value: Double) = "%.1f".format(value)

    private fun EmbeddedServer<*, *>.stopQuietly() = stop(0, 0)

    private suspend fun HttpClient.chat(
        port: Int,
        stream: Boolean,
    ): HttpResponse =
        post("http://127.0.0.1:$port/v1/chat") {
            header(HttpHeaders.Authorization, "Bearer $VALID_TOKEN")
            contentType(ContentType.Application.Json)
            setBody("""{"stream":$stream,"messages":[{"role":"user","content":[{"type":"text","text":"hi"}]}]}""")
        }

    /** 受信時刻から最初の`content_delta`イベント行を読み終えるまでのnano秒。 */
    private suspend fun HttpClient.firstChunkNanos(port: Int): Long {
        val response = chat(port, stream = true)
        val channel = response.bodyAsChannel()
        var line = channel.readUTF8Line()
        while (line != null && !line.startsWith("event: ${apap.gateway.sse.SseEventName.CONTENT_DELTA}")) {
            line = channel.readUTF8Line()
        }
        val elapsed = System.nanoTime() - receivedAtNanos.get()
        channel.cancel(null)
        return elapsed
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** `execute`入口の時刻だけを記録する薄いデコレータ。応答内容には一切干渉しない。 */
    private class TimestampingAdapter(
        private val delegate: ProviderAdapter,
        private val enteredAtNanos: AtomicLong,
    ) : ProviderAdapter by delegate {
        override suspend fun execute(request: AdapterRequest): AdapterResponse {
            enteredAtNanos.set(System.nanoTime())
            return delegate.execute(request)
        }

        override suspend fun executeStream(request: AdapterRequest): ProviderAdapter.AdapterStream {
            enteredAtNanos.set(System.nanoTime())
            return delegate.executeStream(request)
        }
    }

    private companion object {
        /** JIT・接続確立・クラスロードの影響を測定から外すためのウォームアップ回数。 */
        const val WARMUP_REQUESTS = 500

        /** 非Streaming計測の試行回数。p99は上位1%（20件）で決まるため、この規模で十分な分解能がある。 */
        const val MEASURED_REQUESTS = 2_000

        /**
         * Streaming計測の試行回数。1試行がSSE接続の確立・読み取り・切断を伴い
         * 非Streamingの十数倍のコストになるため、別の値を持つ（p99は上位10件で決まる）。
         */
        const val MEASURED_STREAM_REQUESTS = 1_000

        /** Streamingのウォームアップ回数。 */
        const val WARMUP_STREAM_REQUESTS = 200
        const val THROUGHPUT_SECONDS = 10L
        const val CONCURRENCY = 64

        /**
         * 計測中にレート制限を効かせないための値。APAP自身の処理コストを測るのが目的で、
         * トークン待ちの時間を測ってしまうと計測の意味が失われる。
         */
        const val UNTHROTTLED_CAPACITY = 1_000_000
        const val UNTHROTTLED_REFILL = 1_000_000.0

        /** `RateLimiterConfig`の既定値（出荷時の実力を測る1本で使う）。 */
        const val SHIPPED_DEFAULT_CAPACITY = 60
        const val SHIPPED_DEFAULT_REFILL = 1.0

        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLI = 1_000_000.0
        const val P50 = 0.50
        const val P90 = 0.90
        const val P99 = 0.99
    }
}
