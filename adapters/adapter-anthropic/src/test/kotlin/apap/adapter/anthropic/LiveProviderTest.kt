package apap.adapter.anthropic

import apap.adapter.spi.AdapterChunkType
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.CredentialRef
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.adapter.spi.TextContentPart
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.time.Duration

/**
 * **実APIを叩く**テスト。既定では実行されない（CIでも実行されない）。
 *
 * ## 実行方法
 *
 * ```bash
 * export APAP_LIVE_PROVIDER_TEST=1
 * export APAP_PROVIDER_API_KEY='<あなたの鍵>'   # このリポジトリには絶対に置かないこと
 * ./gradlew :adapters:adapter-anthropic:test --tests '*LiveProviderTest*'
 * ```
 *
 * [EnabledIfEnvironmentVariable]でゲートしているため、環境変数が無ければJUnitが
 * テストごと無効化する。`Assumptions`でのスキップにしていないのは、スキップは
 * 「実行して条件を満たさなかった」に見え、**実行していない**ことが伝わりにくいため。
 *
 * ## 記録の生成
 *
 * `APAP_RECORD_DIR` を指定すると、往復した内容を同ディレクトリへ書き出す。
 * 書き出した内容は**必ず目で確認し、鍵・組織ID・個人情報をマスクしてから**
 * `src/test/resources/recordings/` へ置くこと（[RecordingProvenanceTest]が
 * 明らかな漏れは検出するが、完全ではない）。
 */
@EnabledIfEnvironmentVariable(named = "APAP_LIVE_PROVIDER_TEST", matches = "1")
class LiveProviderTest {
    @Test
    fun `chat round trip against the real api`() {
        val adapter = liveAdapter()
        val response =
            runBlocking {
                adapter.execute(
                    requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(
                        modelName = liveModelName(),
                        messages = listOf(userMessage("Reply with the single word: ok")),
                        input = listOf(TextContentPart("Reply with the single word: ok")),
                        timeout = LIVE_TIMEOUT,
                    ),
                )
            }
        assertTrue(response.output.isNotEmpty(), "実APIから空応答が返りました")
        assertTrue(response.usage.inputTokens.value > 0, "usage が取得できていません")
    }

    @Test
    fun `streaming round trip against the real api`() {
        val adapter = liveAdapter()
        val chunks =
            runBlocking {
                val stream =
                    adapter.executeStream(
                        requestFor(AnthropicAdapter.CAPABILITY_STREAMING).copy(
                            modelName = liveModelName(),
                            messages = listOf(userMessage("Count from 1 to 5.")),
                            input = listOf(TextContentPart("Count from 1 to 5.")),
                            timeout = LIVE_TIMEOUT,
                        ),
                    )
                buildList {
                    while (true) add(stream.next() ?: break)
                }
            }
        assertEquals(AdapterChunkType.MESSAGE_START, chunks.first().type)
        assertEquals(AdapterChunkType.MESSAGE_END, chunks.last().type)
        assertTrue(
            chunks.any { it.type == AdapterChunkType.USAGE },
            "ストリーム末尾で usage が取れていません（推定へのフォールバックが必要かの判断材料）",
        )
    }

    @Test
    fun `discoverModels against the real api`() {
        val models = runBlocking { liveAdapter().discoverModels() }
        assertTrue(models.isNotEmpty(), "モデル一覧が空です")
    }

    private fun liveAdapter(): AnthropicAdapter {
        val key = System.getenv(API_KEY_ENV) ?: error("$API_KEY_ENV が設定されていません")
        val recordDir = System.getenv(RECORD_DIR_ENV)?.let { File(it).apply { mkdirs() } }
        val base = KtorHttpTransport(AnthropicAdapter.DEFAULT_BASE_URL)
        val transport = if (recordDir == null) base else RecordingHttpTransport(base, recordDir)
        return AnthropicAdapter(transportFactory = { transport }).apply {
            initialize(liveConfig(), liveSecrets(key))
        }
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

    private fun liveSecrets(key: String): SecretAccessor =
        object : SecretAccessor {
            override fun resolve(ref: CredentialRef): SecretValue = SecretValue(key.toCharArray())
        }

    private fun liveModelName(): String = System.getenv(MODEL_ENV) ?: error("$MODEL_ENV に実在するモデル名を設定してください")

    private companion object {
        const val API_KEY_ENV = "APAP_PROVIDER_API_KEY"
        const val MODEL_ENV = "APAP_PROVIDER_MODEL"
        const val RECORD_DIR_ENV = "APAP_RECORD_DIR"
        val LIVE_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
