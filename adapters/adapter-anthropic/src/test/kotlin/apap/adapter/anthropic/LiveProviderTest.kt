package apap.adapter.anthropic

import apap.adapter.spi.AdapterChunk
import apap.adapter.spi.AdapterChunkType
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.AdapterException
import apap.adapter.spi.CredentialRef
import apap.adapter.spi.CredentialState
import apap.adapter.spi.GenerationParams
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.adapter.spi.TextContentPart
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.port.SecretStore
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * **実APIを叩く**計測ハーネス。既定では実行されない（CIでも実行されない）。
 *
 * 目的は「動いた」ことの確認ではなく、docs/adapter-spi-findings.md の **[要実測]** 項目を
 * 数値で確定させること。各テストは計測結果を[MEASUREMENT_DIR_ENV]配下のJSONへ書き出す。
 *
 * ## 実行方法
 *
 * 鍵は**環境変数から[SecretStore]経由**で解決する（リポジトリには置かない）。
 *
 * ```bash
 * export APAP_LIVE_PROVIDER_TEST=1
 * export APAP_PROVIDER_API_KEY='...'      # このリポジトリには絶対に置かないこと
 * export APAP_PROVIDER_MODEL='...'        # 実在するモデル名
 * export APAP_RECORD_DIR="$(mktemp -d)"        # 任意: 記録を残す
 * export APAP_MEASUREMENT_DIR="$(mktemp -d)"   # 任意: 計測値を残す
 * ./gradlew :adapters:adapter-anthropic:test --tests '*LiveProviderTest*'
 * ```
 *
 * [EnabledIfEnvironmentVariable]でゲートしているため、環境変数が無ければJUnitが
 * テストごと無効化する。`Assumptions`でのスキップにしていないのは、スキップは
 * 「実行して条件を満たさなかった」に見え、**実行していない**ことが伝わりにくいため。
 *
 * ## コストを抑える設計
 *
 * 全ての生成呼出で`max_tokens`を[LIVE_MAX_TOKENS]に絞り、プロンプトも最短にしている。
 * レート制限（429）を**意図的に踏みに行くことはしない**——実Providerへ過剰な負荷を
 * かける行為であり、`retry-after`の実測のためだけに行うのは割に合わない。
 * この項目が未実測として残る理由は findings に明記する。
 *
 * ## 鍵の非混入
 *
 * [assertNoKeyLeakedIntoArtifacts]が、**実際の鍵文字列**が記録・計測の出力ファイルへ
 * 現れないことを全テスト終了後に検査する。静的パターンに頼る[RecordingProvenanceTest]と違い、
 * 本物の値そのものを探すため見落としがない。
 */
@EnabledIfEnvironmentVariable(named = "APAP_LIVE_PROVIDER_TEST", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveProviderTest {
    private val mapper = ObjectMapper()

    @Test
    fun `chat round trip records usage, finish reason and latency`() {
        val adapter = liveAdapter()
        val started = Instant.now()
        val response =
            runBlocking {
                adapter.execute(minimalRequest(AnthropicAdapter.CAPABILITY_CHAT, "Reply with: ok"))
            }
        val elapsed = Duration.between(started, Instant.now())

        assertTrue(response.output.isNotEmpty(), "実APIから空応答が返りました")
        assertTrue(response.usage.inputTokens.value > 0, "usage.inputTokens が取得できていません")

        record(
            "chat",
            mapOf(
                "latencyMillis" to elapsed.toMillis(),
                "inputTokens" to response.usage.inputTokens.value,
                "outputTokens" to response.usage.outputTokens.value,
                "usageEstimated" to response.usage.estimated,
                "finishReason" to response.finishReason.name,
                "hasProviderRequestId" to (response.providerRequestId != null),
            ),
        )
    }

    /**
     * バックプレッシャの実測。チャンクを受け取るたびに[CONSUMER_DELAY]だけ待ち、
     * 到着間隔と総経過時間を記録する。
     *
     * **限界**: クライアント側からTCPの受信窓が実際に閉じたことは観測できない。
     * ここで確かめられるのは「消費側が待てば全体が待たされる（＝先読みで全部流し込まれない）」
     * という必要条件までである。findings にもその旨を書く。
     */
    @Test
    fun `streaming with a slow consumer is paced by the consumer`() {
        val adapter = liveAdapter()
        val arrivals = mutableListOf<Long>()
        val chunks = mutableListOf<AdapterChunk>()
        val started = Instant.now()

        runBlocking {
            val stream = adapter.executeStream(minimalRequest(AnthropicAdapter.CAPABILITY_STREAMING, "Count 1 to 5."))
            while (true) {
                val chunk = stream.next() ?: break
                arrivals += Duration.between(started, Instant.now()).toMillis()
                chunks += chunk
                delay(CONSUMER_DELAY.toMillis())
            }
        }
        val elapsed = Duration.between(started, Instant.now())

        assertEquals(AdapterChunkType.MESSAGE_START, chunks.first().type)
        assertEquals(AdapterChunkType.MESSAGE_END, chunks.last().type)

        val usageChunkIndex = chunks.indexOfFirst { it.type == AdapterChunkType.USAGE }
        val usage = chunks.getOrNull(usageChunkIndex)?.usage

        record(
            "streaming",
            mapOf(
                "chunkCount" to chunks.size,
                "chunkTypes" to chunks.map { it.type.name },
                "arrivalMillis" to arrivals,
                "totalElapsedMillis" to elapsed.toMillis(),
                "consumerDelayMillis" to CONSUMER_DELAY.toMillis(),
                // 消費側の待ち時間の総和より短ければ、先読みで一気に流し込まれている＝背圧が効いていない。
                "pacedByConsumer" to (elapsed.toMillis() >= (chunks.size - 1) * CONSUMER_DELAY.toMillis()),
                "usageChunkIndexFromEnd" to (chunks.size - 1 - usageChunkIndex),
                "usageInputTokens" to (usage?.inputTokens?.value ?: -1),
                "usageOutputTokens" to (usage?.outputTokens?.value ?: -1),
                "heartbeatSeen" to chunks.any { it.type == AdapterChunkType.HEARTBEAT },
            ),
        )
    }

    @Test
    fun `discoverModels returns a usable catalogue`() {
        val models = runBlocking { liveAdapter().discoverModels() }
        assertTrue(models.isNotEmpty(), "モデル一覧が空です")
        record(
            "discoverModels",
            mapOf(
                "count" to models.size,
                // 実際の値ではなく「返ってきたか」を記録する（モデル名は製品情報のため値は残さない）。
                "allHaveContextWindow" to models.all { it.contextWindow > 0 },
                "allHaveMaxOutputTokens" to models.all { it.maxOutputTokens > 0 },
                "distinctVersionsEqualIds" to models.all { it.version == it.modelName },
            ),
        )
    }

    @Test
    fun `healthCheck responds quickly enough for a 30 second cycle`() {
        val adapter = liveAdapter()
        val started = Instant.now()
        val result = runBlocking { adapter.healthCheck() }
        val elapsed = Duration.between(started, Instant.now())

        record(
            "healthCheck",
            mapOf(
                "status" to result.status.name,
                "measuredMillis" to elapsed.toMillis(),
                "reportedLatencyMillis" to result.latency.toMillis(),
            ),
        )
        assertTrue(elapsed < HEALTH_CHECK_BUDGET, "healthCheck が $HEALTH_CHECK_BUDGET を超えました: $elapsed")
    }

    /** 存在しないモデル名。トークンを消費せず、実エラーボディの形を1回で確認できる。 */
    @Test
    fun `an unknown model produces a classifiable error`() {
        val adapter = liveAdapter()
        val thrown =
            runCatching {
                runBlocking {
                    adapter.execute(
                        minimalRequest(AnthropicAdapter.CAPABILITY_CHAT, "x")
                            .copy(modelName = "definitely-not-a-real-model-$UNIQUE_SUFFIX"),
                    )
                }
            }.exceptionOrNull()

        val adapterException = thrown as? AdapterException
        record(
            "errorUnknownModel",
            mapOf(
                "threwAdapterException" to (adapterException != null),
                "category" to (adapterException?.category?.name ?: thrown?.let { it::class.simpleName } ?: "none"),
                "retryAfterSeconds" to (adapterException?.retryAfter?.seconds ?: -1),
                "hasProviderDetail" to (adapterException?.providerDetail != null),
            ),
        )
        assertEquals(
            AdapterErrorCategory.MODEL_ERROR,
            adapterException?.category,
            "存在しないモデルが MODEL_ERROR へ写っていません: $thrown",
        )
    }

    /** 明らかに不正な鍵。トークンを消費せず、401の実ボディを確認できる。 */
    @Test
    fun `an invalid credential produces AUTH_ERROR without leaking the key`() {
        val adapter = adapterWith(constantSecrets("not-a-valid-key-$UNIQUE_SUFFIX"))
        val thrown =
            runCatching {
                runBlocking { adapter.execute(minimalRequest(AnthropicAdapter.CAPABILITY_CHAT, "x")) }
            }.exceptionOrNull()

        val adapterException = thrown as? AdapterException
        record(
            "errorInvalidCredential",
            mapOf(
                "category" to (adapterException?.category?.name ?: "none"),
                "retryAfterSeconds" to (adapterException?.retryAfter?.seconds ?: -1),
            ),
        )
        assertEquals(AdapterErrorCategory.AUTH_ERROR, adapterException?.category, "401 が AUTH_ERROR へ写っていません")
        val haystack = "${adapterException?.message}|${adapterException?.providerDetail}|$adapterException"
        assertFalse(haystack.contains("not-a-valid-key"), "提示した鍵が例外へ混入しています: $haystack")
    }

    /**
     * 全テスト終了後に、**実際の鍵文字列**が生成物へ現れないことを確認する。
     * 静的パターン検査（[RecordingProvenanceTest]）では見つけられない漏れを塞ぐ。
     */
    @AfterAll
    fun assertNoKeyLeakedIntoArtifacts() {
        val key = System.getenv(API_KEY_ENV) ?: return
        val dirs = listOfNotNull(recordDir(), measurementDir())
        val leaked =
            dirs.flatMap { dir ->
                dir
                    .walkTopDown()
                    .filter { it.isFile }
                    .filter { it.readText().contains(key) }
                    .map { it.path }
                    .toList()
            }
        assertTrue(leaked.isEmpty(), "APIキーの実値が生成物へ書き出されました: $leaked")
    }

    // --- 組み立て ---------------------------------------------------------------------------

    private fun minimalRequest(
        capability: apap.adapter.spi.CapabilityId,
        prompt: String,
    ) = requestFor(capability).copy(
        modelName = liveModelName(),
        messages = listOf(userMessage(prompt)),
        input = listOf(TextContentPart(prompt)),
        // コストを抑える。max_tokensは実APIで必須のため、既定値任せにしない（ADR-0040）。
        params = GenerationParams(maxTokens = LIVE_MAX_TOKENS),
        timeout = LIVE_TIMEOUT,
    )

    private fun liveAdapter(): AnthropicAdapter = adapterWith(secretsFromStore())

    private fun adapterWith(secrets: SecretAccessor): AnthropicAdapter {
        val recordDir = recordDir()
        val base = KtorHttpTransport(AnthropicAdapter.DEFAULT_BASE_URL)
        val transport = if (recordDir == null) base else RecordingHttpTransport(base, recordDir)
        return AnthropicAdapter(transportFactory = { transport }).apply { initialize(liveConfig(), secrets) }
    }

    private fun liveConfig(): AdapterConfig {
        val region = Region.of("global", RegionCodeTable(setOf("global")))
        return AdapterConfig(
            providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FZ2"),
            endpoints = listOf(Endpoint("live", region, AnthropicAdapter.DEFAULT_BASE_URL, 100)),
            rateLimits = RateLimits(60, 100_000, 4),
            regions = setOf(region),
        )
    }

    /**
     * 鍵は[SecretStore]（ドメインのPort）経由で解決する。テスト内で環境変数を直接読んで
     * Adapterへ渡すと、本番と違う経路になり「Secret Store経由で扱う」という前提が崩れる。
     */
    private fun secretsFromStore(): SecretAccessor {
        val store =
            object : SecretStore {
                override fun resolve(ref: CredentialRef): CharArray =
                    (System.getenv(API_KEY_ENV) ?: error("$API_KEY_ENV が設定されていません")).toCharArray()

                override fun store(
                    ref: CredentialRef,
                    value: CharArray,
                ) = error("live test does not write secrets")
            }
        return object : SecretAccessor {
            override fun resolve(ref: CredentialRef): SecretValue = SecretValue(store.resolve(ref))
        }
    }

    private fun constantSecrets(value: String): SecretAccessor =
        object : SecretAccessor {
            override fun resolve(ref: CredentialRef): SecretValue = SecretValue(value.toCharArray())
        }

    private fun liveModelName(): String = System.getenv(MODEL_ENV) ?: error("$MODEL_ENV に実在するモデル名を設定してください")

    private fun recordDir(): File? = System.getenv(RECORD_DIR_ENV)?.let { File(it).apply { mkdirs() } }

    private fun measurementDir(): File? = System.getenv(MEASUREMENT_DIR_ENV)?.let { File(it).apply { mkdirs() } }

    /** 計測値をJSONで残す。findings へ転記する際の一次情報になる。 */
    private fun record(
        name: String,
        values: Map<String, Any?>,
    ) {
        val dir = measurementDir() ?: return
        File(dir, "$name.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(values))
    }

    private companion object {
        const val API_KEY_ENV = "APAP_PROVIDER_API_KEY"
        const val MODEL_ENV = "APAP_PROVIDER_MODEL"
        const val RECORD_DIR_ENV = "APAP_RECORD_DIR"
        const val MEASUREMENT_DIR_ENV = "APAP_MEASUREMENT_DIR"

        /** コスト抑制。応答の中身は検証対象ではなく、往復の形だけを見る。 */
        const val LIVE_MAX_TOKENS = 16

        val LIVE_TIMEOUT: Duration = Duration.ofSeconds(60)
        val CONSUMER_DELAY: Duration = Duration.ofMillis(120)
        val HEALTH_CHECK_BUDGET: Duration = Duration.ofSeconds(30)

        /** 実在しない値であることを確実にするための接尾辞。 */
        const val UNIQUE_SUFFIX = "apap-live-probe"

        /** CredentialRefは[SecretStore]の引数に必要だが、live計測では参照名を1本しか使わない。 */
        @Suppress("unused")
        val LIVE_REF = CredentialRef("live-key", 1, CredentialState.ACTIVE)
    }
}
