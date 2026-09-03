package apap.runtime

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.AdapterResponse
import apap.adapter.spi.HealthResult
import apap.adapter.spi.ProviderAdapter
import apap.domain.event.DomainEvent
import apap.domain.event.ProviderHealthChanged
import apap.domain.model.modelcatalog.AliasTarget
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.AliasId
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.infrastructure.eventbus.SynchronousEventBus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 05_シーケンス設計.md のうち、P11時点でE2Eテストが無かった3本を埋める。
 *
 * | シーケンス | P11時点 | 本テスト |
 * |---|---|---|
 * | 5.6 Fallback | 単体（`FallbackEngineTest`）のみ | ビルダ経由で候補切替を確認 |
 * | 5.8 Provider切替 | 単体（`RoutingEngineTest`）のみ | Alias切替が実行先を変えることを確認 |
 * | 5.10 Health Check | **テスト皆無** | 周期タスクが健全性を反映することを確認 |
 *
 * いずれも「部品は単体テストで緑」だった。本番の組立て（[ApapEngineBuilder]）を通したときに
 * 実際に動くかは別問題であり、P11ではその差から3件の不具合が見つかっている。
 */
class SequenceFlowE2ETest {
    private val capabilityId = CapabilityId("chat")

    @Test
    fun `5-6 - a failing primary candidate falls back to the next provider`() {
        // plugin-a は必ず失敗し、plugin-b は成功する。priorityでaを先に選ばせる。
        val failing =
            EngineFixture.mock(
                MockAdapterConfig(
                    supportedCapabilities = setOf(capabilityId),
                    forcedErrorCategory = AdapterErrorCategory.PROVIDER_UNAVAILABLE,
                ),
            )
        val healthy = CountingAdapter(EngineFixture.mock(mockConfig()))
        val fixture =
            EngineFixture.build(capabilityId, mapOf("plugin-a" to failing, "plugin-b" to healthy))

        fixture.use {
            runBlocking {
                EngineFixture.registerActive(fixture, capabilityId, pluginId = "plugin-a", priority = 90)
                EngineFixture.registerActive(fixture, capabilityId, pluginId = "plugin-b", priority = 10)

                val response = fixture.engine.execute(EngineFixture.request(capabilityId))

                assertTrue(
                    healthy.calls.get() > 0,
                    "1つ目の候補が失敗したのに次の候補が呼ばれていません（Fallbackが実行経路で効いていない）。",
                )
                assertTrue(
                    response.output.isNotEmpty(),
                    "Fallback後の応答が空です: ${response.finishReason}",
                )
            }
        }
    }

    @Test
    fun `5-8 - switching the alias target changes which provider serves the request`() {
        val first = CountingAdapter(EngineFixture.mock(mockConfig()))
        val second = CountingAdapter(EngineFixture.mock(mockConfig()))
        val fixture = EngineFixture.build(capabilityId, mapOf("plugin-a" to first, "plugin-b" to second))

        fixture.use {
            runBlocking {
                val modelA = EngineFixture.registerActive(fixture, capabilityId, pluginId = "plugin-a", priority = 90)
                val modelB = EngineFixture.registerActive(fixture, capabilityId, pluginId = "plugin-b", priority = 10)

                // Aliasを modelA に向けて実行 → plugin-a が呼ばれる。
                assignAlias(fixture, modelA)
                fixture.engine.execute(EngineFixture.request(capabilityId).copy(modelAlias = ALIAS))
                val afterFirst = first.calls.get() to second.calls.get()

                // Alias を modelB へ切り替える → 以降は plugin-b が呼ばれる。
                assignAlias(fixture, modelB)
                fixture.engine.execute(EngineFixture.request(capabilityId).copy(modelAlias = ALIAS))

                assertEquals(
                    afterFirst.first,
                    first.calls.get(),
                    "Alias切替後も旧Modelが呼ばれています（切替が実行経路へ反映されていない）。",
                )
                assertTrue(
                    second.calls.get() > afterFirst.second,
                    "Alias切替後に新Modelが呼ばれていません（first=${first.calls.get()}, second=${second.calls.get()}）。",
                )
            }
        }
    }

    @Test
    fun `5-10 - the health check task publishes a change and it reaches the health endpoint`() {
        val events = ConcurrentLinkedQueue<DomainEvent>()
        val bus = SynchronousEventBus()
        bus.subscribe { events += it }

        // 登録時はUP（DOWNのままだとcompleteValidationが失敗し、そもそもACTIVEにできない）。
        // 登録後にDOWNへ切り替え、周期タスクがその変化を拾うことを見る。
        val probe = SwitchableHealthAdapter(EngineFixture.mock(mockConfig()))
        val repositories = ApapRepositories()
        val engine =
            ApapEngineBuilder(repositories = repositories)
                .adapterRegistry(
                    object : apap.provider.AdapterRegistry {
                        override fun resolve(pluginId: String) =
                            apap.provider.ResolvedPlugin(
                                probe,
                                apap.adapter.spi.plugin.PluginManifest(
                                    pluginId = "plugin-a",
                                    version =
                                        apap.domain.model.vo
                                            .SemVer(1, 0, 0),
                                    spiVersionRange =
                                        apap.adapter.spi.plugin.SemVerRange
                                            .parse(">=1.0"),
                                    entryPoint = "test.Entry",
                                    capabilities = setOf(capabilityId),
                                    authTypes = setOf("api_key"),
                                    signature = "sig",
                                ),
                            )
                    },
                ).eventBus(ApapEngineBuilder.DomainEventBus(bus, bus))
                .build()
        val fixture = EngineFixture.Fixture(engine, repositories)

        fixture.use {
            runBlocking {
                EngineFixture.registerActive(fixture, capabilityId)
                probe.status = ProviderHealthStatus.DOWN

                val task =
                    engine.scheduledTasks.first { it.name == "provider-health-check" }
                // ADR-0032: 駆動するのは宿主。ここではテストが宿主の役をする。
                task.runOnce()

                val changed = events.filterIsInstance<ProviderHealthChanged>()
                assertTrue(
                    changed.isNotEmpty(),
                    "健全性チェックを1周回してもProviderHealthChangedが発火していません" +
                        "（FR-PRV-006が動いていない）。発火したイベント: ${events.map { it::class.simpleName }}",
                )
                assertEquals(ProviderHealthStatus.DOWN, changed.first().to)

                // 集約側（/health/providers）まで届いていること。
                assertEquals(
                    "DOWN",
                    engine.health
                        .providerHealth()
                        .state.name,
                    "ProviderHealthChangedがProviderHealthAggregatorへ届いていません。",
                )

                // 2周目は状態が変わらないので再発火しない（イベントの無駄打ちをしない）。
                val before = changed.size
                task.runOnce()
                assertEquals(
                    before,
                    events.filterIsInstance<ProviderHealthChanged>().size,
                    "状態が変わっていないのにProviderHealthChangedを再発火しています。",
                )
            }
        }
    }

    private fun mockConfig() = MockAdapterConfig(supportedCapabilities = setOf(capabilityId))

    /** `healthCheck()`の戻りをテストから切り替えられるデコレータ。 */
    private class SwitchableHealthAdapter(
        private val delegate: ProviderAdapter,
    ) : ProviderAdapter by delegate {
        @Volatile
        var status: ProviderHealthStatus = ProviderHealthStatus.UP

        override suspend fun healthCheck(): HealthResult = HealthResult(status, Duration.ofMillis(1), "test probe")
    }

    /** Aliasを1つのModelへ100%向ける。切替は同じaliasIdへの再割当てで表現する。 */
    private fun assignAlias(
        fixture: EngineFixture.Fixture,
        modelId: ModelId,
    ) {
        fixture.engine.admin.models.assignAlias(
            tenantId = EngineFixture.TENANT,
            aliasId = AliasId(ALIAS_ID),
            name = ALIAS,
            targets = listOf(AliasTarget(modelId, weight = 100)),
        )
    }

    /** 何回呼ばれたかを数えるだけのデコレータ。どの候補が実際に使われたかの直接の証拠になる。 */
    private class CountingAdapter(
        private val delegate: ProviderAdapter,
    ) : ProviderAdapter by delegate {
        val calls = AtomicInteger(0)

        override suspend fun execute(request: AdapterRequest): AdapterResponse {
            calls.incrementAndGet()
            return delegate.execute(request)
        }
    }

    private companion object {
        const val ALIAS = "chat-default"
        const val ALIAS_ID = "01ARZ3NDEKTSV4RRFFQ69G5FZ9"
    }
}
