package apap.runtime

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.mock.MockProviderAdapter
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.plugin.PluginManifest
import apap.adapter.spi.plugin.SemVerRange
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.SemVer
import apap.domain.port.SecretStore
import apap.provider.ProviderAdapterFactory
import apap.provider.ProviderAdapterProvisioner
import apap.provider.ResolvedPlugin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR-0041: **Adapterのライフサイクルが本番の入口（[ApapEngineBuilder]）から駆動される**こと。
 *
 * `ProviderAdapterProvisioner`単体の性質は`ProviderAdapterProvisionerTest`が見る。ここで見るのは
 * 配線——ADR-0041が記録した欠陥は「型は揃っているのに本番の誰も呼ばない」ことであり、
 * この種の欠陥は単体テストをいくら足しても検出できない（P11で3件、以後も繰り返している）。
 * したがって**必ずビルダ経由で**確認する。
 */
class AdapterLifecycleWiringTest {
    private val capabilityId = CapabilityId("chat")

    @Test
    fun `provider activation through the builder initializes an adapter for that provider`(): Unit =
        runBlocking {
            val factory = CountingFactory(capabilityId)
            val provisioner = ProviderAdapterProvisioner(factory, ConstantSecretStore)
            EngineFixture
                .build(capabilityId, emptyMap()) { adapterRegistry(provisioner) }
                .use { fixture ->
                    EngineFixture.registerActive(fixture, capabilityId)

                    // 有効化の過程で2つ作られる: VALIDATINGでSTANDBYのCredentialを持つもの、
                    // 検証合格でACTIVEへ昇格したあとの差し替え。使われるのは後者で、前者は畳まれる。
                    assertEquals(2, factory.created.size, factory.created.toString())
                    assertTrue(factory.created.all { it.initialized }, "初期化されずに出たインスタンスがあります")
                    assertEquals(1, factory.created.first().shutdownCount, "差し替え前が畳まれていません")
                    assertEquals(0, factory.created.last().shutdownCount, "使用中のインスタンスが畳まれています")
                    assertEquals(1, provisioner.provisionedProviders().size)
                }
        }

    @Test
    fun `two providers on the same plugin get their own adapter through the builder`(): Unit =
        runBlocking {
            val factory = CountingFactory(capabilityId)
            val provisioner = ProviderAdapterProvisioner(factory, ConstantSecretStore)
            EngineFixture
                .build(capabilityId, emptyMap()) { adapterRegistry(provisioner) }
                .use { fixture ->
                    EngineFixture.registerActive(fixture, capabilityId, pluginId = "plugin-a", providerName = "p1")
                    EngineFixture.registerActive(fixture, capabilityId, pluginId = "plugin-a", providerName = "p2")

                    val live = provisioner.provisionedProviders().map { provisioner.resolve(it).adapter }
                    assertEquals(2, live.size, "Providerごとのインスタンスになっていません")
                    assertNotSame(live[0], live[1], "2つのProviderが1つのインスタンスを共有しています")
                }
        }

    /**
     * `close()`はAdapterも畳む。Adapterは実HTTPクライアント（コネクションプール・スレッド）を
     * 持ちうるため、畳まないまま宿主のプロセスに残す（不変条件6の趣旨）。
     */
    @Test
    fun `closing the engine shuts every provisioned adapter down`(): Unit =
        runBlocking {
            val factory = CountingFactory(capabilityId)
            val provisioner = ProviderAdapterProvisioner(factory, ConstantSecretStore)
            val fixture = EngineFixture.build(capabilityId, emptyMap()) { adapterRegistry(provisioner) }
            EngineFixture.registerActive(fixture, capabilityId)
            val inUse = factory.created.last()
            assertEquals(0, inUse.shutdownCount, "まだ閉じていないのにshutdownされています")

            fixture.engine.close()

            assertEquals(1, inUse.shutdownCount, "close()でAdapterが畳まれていません")
            assertTrue(provisioner.provisionedProviders().isEmpty())
        }

    private class CountingFactory(
        private val capabilityId: CapabilityId,
    ) : ProviderAdapterFactory {
        val created = mutableListOf<LifecycleRecordingAdapter>()

        override fun instantiate(pluginId: String): ResolvedPlugin {
            val adapter =
                LifecycleRecordingAdapter(
                    MockProviderAdapter(MockAdapterConfig(supportedCapabilities = setOf(capabilityId))),
                )
            created += adapter
            return ResolvedPlugin(
                adapter,
                PluginManifest(
                    pluginId = pluginId,
                    version = SemVer(1, 0, 0),
                    spiVersionRange = SemVerRange.parse(">=1.0"),
                    entryPoint = "apap.adapter.mock.MockProviderAdapter",
                    capabilities = setOf(capabilityId),
                    authTypes = setOf("api_key"),
                    signature = "sig",
                ),
            )
        }
    }

    /** `initialize` / `shutdown` の呼出だけを記録し、残りはadapter-mockへ委譲する。 */
    private class LifecycleRecordingAdapter(
        private val delegate: MockProviderAdapter,
    ) : ProviderAdapter by delegate {
        var initialized = false
            private set
        var shutdownCount = 0
            private set

        override fun initialize(
            config: apap.adapter.spi.AdapterConfig,
            secrets: apap.adapter.spi.SecretAccessor,
        ) {
            delegate.initialize(config, secrets)
            initialized = true
        }

        override fun shutdown() {
            delegate.shutdown()
            shutdownCount++
        }
    }

    private object ConstantSecretStore : SecretStore {
        override fun resolve(ref: CredentialRef): CharArray = "secret".toCharArray()

        override fun store(
            ref: CredentialRef,
            value: CharArray,
        ) = error("not used")
    }
}
