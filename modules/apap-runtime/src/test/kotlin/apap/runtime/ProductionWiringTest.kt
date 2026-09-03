package apap.runtime

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.mock.MockProviderAdapter
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.adapter.spi.plugin.PluginManifest
import apap.adapter.spi.plugin.SemVerRange
import apap.api.ApapRequest
import apap.domain.model.audit.AuditSearchCriteria
import apap.domain.model.cost.PriceBook
import apap.domain.model.cost.PriceEntry
import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.TenantId
import apap.provider.AdapterRegistry
import apap.provider.PluginNotFoundException
import apap.provider.RegisterModelCommand
import apap.provider.RegisterProviderCommand
import apap.provider.ResolvedPlugin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * P11で発見した「実装済みだが本番配線に存在しない」3件が、**本番の入口
 * （[ApapEngineBuilder]）を通して実際に機能する**ことを検証する。
 *
 * | 番号 | 内容 |
 * |---|---|
 * | P11-F1 | `AuditEngine`がどこからも構築されず、監査ログが1件も記録されなかった |
 * | P11-F2 | `ProviderHealthChanged`を発火する主体が無く、健全性監視が動かなかった |
 * | P11-F10 | `RateLimiter.configure()`が呼ばれず、Providerの`rateLimits`が反映されなかった |
 *
 * 3件に共通するのは「単体テストは緑」「本番では動かない」であり、
 * 単体テストを増やしても検出できない。**必ずビルダ経由で**検証する。
 */
class ProductionWiringTest {
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FE0")
    private val capabilityId = CapabilityId("chat")

    @Test
    fun `F1 - audit records are written through the production wiring`() {
        val repositories = ApapRepositories()
        val engine = buildEngine(repositories)

        runBlocking {
            setUpActiveProviderAndModel(engine, repositories, rpm = 600)
            engine.execute(request())
        }
        // close()が監査書き込みの完了を待ってからスレッドを止める（非同期書き込みのため）。
        engine.close()

        val records = repositories.auditRepository.search(AuditSearchCriteria(tenantId = tenantId))
        assertTrue(
            records.isNotEmpty(),
            "監査ログが1件も記録されていません。AuditEngineが本番配線に接続されていない可能性があります（P11-F1）。",
        )
        assertEquals(capabilityId.value, records.first().capabilityId)
    }

    @Test
    fun `F10 - the registered provider rate limits are applied to the limiter`() {
        val repositories = ApapRepositories()
        val engine = buildEngine(repositories)

        // rpm=60 → 容量60・毎秒1補充。バーストの60件は即時、それを超えると補充待ちになる。
        runBlocking { setUpActiveProviderAndModel(engine, repositories, rpm = THROTTLED_RPM) }

        val elapsedMillis =
            engine.use {
                val startedAt = System.nanoTime()
                runBlocking { repeat(THROTTLED_RPM + EXTRA_REQUESTS) { engine.execute(request()) } }
                (System.nanoTime() - startedAt) / NANOS_PER_MILLI
            }

        // 既定バケット（実質無制限）のままなら全件が一瞬で通る。Providerのrpmが
        // リミッタへ反映されている場合にだけ、超過ぶんの補充待ちが観測される。
        assertTrue(
            elapsedMillis >= EXTRA_REQUESTS * MILLIS_PER_REFILL * THROTTLE_TOLERANCE,
            "rpm=$THROTTLED_RPM のProviderに${THROTTLED_RPM + EXTRA_REQUESTS}件流したのに" +
                "${elapsedMillis}msで完了しました。Providerのrate_limitsがRateLimiterへ" +
                "反映されていません（P11-F10: RateLimiter.configure()が呼ばれていない）。",
        )
    }

    @Test
    fun `D2 - scheduled tasks are exposed and the engine does not run them itself`() {
        val repositories = ApapRepositories()
        val engine = buildEngine(repositories)

        engine.use {
            val names = engine.scheduledTasks.map { it.name }
            assertTrue(
                "provider-health-check" in names,
                "Providerの健全性監視タスクが公開されていません（$names）。FR-PRV-006が動きません。",
            )

            // ADR-0032: 埋込ライブラリは常駐スレッドを勝手に起こさない。
            // 駆動しないかぎり何も起きないことを、スレッド名の不在で確認する。
            val runnerThreads = Thread.getAllStackTraces().keys.filter { "apap-scheduled" in it.name }
            assertTrue(
                runnerThreads.isEmpty(),
                "エンジンが周期実行スレッドを勝手に起動しています: ${runnerThreads.map { it.name }}。" +
                    "宿主のライフサイクル管理と衝突します（ADR-0032）。",
            )
        }
    }

    private fun buildEngine(repositories: ApapRepositories): ApapEngine =
        ApapEngineBuilder(repositories = repositories)
            .adapterRegistry(adapterRegistry())
            .build()

    private fun request() =
        ApapRequest(
            tenantId = tenantId,
            principal = "user-1",
            capabilityId = capabilityId,
            input = listOf(ContentPart.Text("hello")),
        )

    private fun adapterRegistry(): AdapterRegistry {
        val adapter = MockProviderAdapter(MockAdapterConfig(supportedCapabilities = setOf(capabilityId)))
        adapter.initialize(
            AdapterConfig(
                ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FE1"),
                listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                RateLimits(RPM, 100_000, 10),
                setOf(region),
            ),
            object : SecretAccessor {
                override fun resolve(ref: CredentialRef): SecretValue = SecretValue("secret".toCharArray())
            },
        )
        return object : AdapterRegistry {
            override fun resolve(pluginId: String): ResolvedPlugin {
                if (pluginId != "plugin-a") throw PluginNotFoundException(pluginId)
                return ResolvedPlugin(
                    adapter,
                    PluginManifest(
                        pluginId = "plugin-a",
                        version = SemVer(1, 0, 0),
                        spiVersionRange = SemVerRange.parse(">=1.0"),
                        entryPoint = "test.Entry",
                        capabilities = setOf(capabilityId),
                        authTypes = setOf("api_key"),
                        signature = "sig",
                    ),
                )
            }
        }
    }

    /** ADR-0021: 単価未登録Modelは候補から除外されるためPriceEntryも登録する。 */
    private suspend fun setUpActiveProviderAndModel(
        engine: ApapEngine,
        repositories: ApapRepositories,
        rpm: Int,
    ) {
        val provider =
            engine.admin.providers.register(
                RegisterProviderCommand(
                    name = "provider-a",
                    adapterPluginId = "plugin-a",
                    spiVersion = SemVer(1, 0, 0),
                    endpoints = listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                    authType = "api_key",
                    credentialRefs = listOf(CredentialRef("secret-ref", 1, CredentialState.STANDBY)),
                    rateLimits = RateLimits(rpm, 1_000_000, 100),
                    priority = 50,
                    regions = setOf(region),
                ),
            )
        engine.admin.providers.beginValidation(provider.providerId)
        engine.admin.providers.completeValidation(provider.providerId)
        engine.admin.providers.enable(provider.providerId, "test setup")

        val model =
            engine.admin.models.register(
                RegisterModelCommand(
                    providerId = provider.providerId,
                    modelName = "model-a",
                    version = "1.0",
                    capabilities = listOf(ModelCapability(capabilityId)),
                    contextWindow = 8000,
                    maxOutputTokens = 1000,
                    regions = setOf(region),
                    priority = 50,
                ),
            )
        engine.admin.models.changeStatus(model.modelId, ModelStatus.TESTING)
        engine.admin.models.changeStatus(model.modelId, ModelStatus.ACTIVE)

        repositories.priceBookRepository.save(
            PriceBook(
                priceBookId = "pb-${model.modelId.value}",
                entries =
                    listOf(
                        PriceEntry(
                            modelId = model.modelId,
                            inputPer1k = Money(BigDecimal("0.01"), "USD"),
                            outputPer1k = Money(BigDecimal("0.01"), "USD"),
                            period = Period(Instant.EPOCH, Instant.parse("9999-01-01T00:00:00Z")),
                        ),
                    ),
            ),
        )
    }

    private companion object {
        /** Adapter登録用。実行経路の制限はProvider登録時のrpmで決まる。 */
        const val RPM = 6_000

        /** 絞りを観測するための低いrpm。容量60・毎秒1補充になる。 */
        const val THROTTLED_RPM = 60

        /** バーストを超えて流す件数。1件につき約1秒の補充待ちが入る。 */
        const val EXTRA_REQUESTS = 3

        const val MILLIS_PER_REFILL = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L

        /** 計測ゆらぎを見込んだ下限の割合。 */
        const val THROTTLE_TOLERANCE = 0.5
    }
}
