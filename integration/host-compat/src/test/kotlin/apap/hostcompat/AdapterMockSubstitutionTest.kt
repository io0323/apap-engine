package apap.hostcompat

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.mock.MockProviderAdapter
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.adapter.spi.plugin.PluginManifest
import apap.adapter.spi.plugin.SemVerRange
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.SemVer
import apap.provider.AdapterRegistry
import apap.provider.PluginNotFoundException
import apap.provider.ResolvedPlugin
import apap.runtime.ApapEngineBuilder
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * `docs/integration/prompt-engine.md` 4章のコード例の実体。
 *
 * ホスト側もテストではadapter-mockを使う想定なので、この例だけは**テストスコープ**に置く
 * （mainのコンパイルクラスパスへadapter-mockを入れると、ホストの本番依存と形が変わる）。
 */
class AdapterMockSubstitutionTest {
    // docs:begin adapter-mock-substitution
    /**
     * `ApapEngineBuilder.adapterRegistry(...)`へ任意の[AdapterRegistry]を渡すと、
     * 実Provider Pluginを配置せずに`ApapEngine`を動かせる。
     *
     * 注意: `Provider.beginValidation`→`completeValidation`はAdapterの
     * `validateCredential`/`healthCheck`/`supportedCapabilities`を実際に呼ぶため、
     * Adapterは`initialize`済みである必要がある。
     */
    fun mockAdapterRegistry(capabilityId: CapabilityId): AdapterRegistry {
        val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
        val adapter = MockProviderAdapter(MockAdapterConfig(supportedCapabilities = setOf(capabilityId)))
        adapter.initialize(
            AdapterConfig(
                ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FD1"),
                listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                RateLimits(600, 100_000, 10),
                setOf(region),
            ),
            object : SecretAccessor {
                override fun resolve(ref: CredentialRef): SecretValue = SecretValue("secret".toCharArray())
            },
        )
        val manifest =
            PluginManifest(
                pluginId = "plugin-a",
                version = SemVer(1, 0, 0),
                spiVersionRange = SemVerRange.parse(">=1.0"),
                entryPoint = "test.Entry",
                capabilities = setOf(capabilityId),
                authTypes = setOf("api_key"),
                signature = "sig",
            )
        return object : AdapterRegistry {
            override fun resolve(pluginId: String): ResolvedPlugin {
                if (pluginId != "plugin-a") throw PluginNotFoundException(pluginId)
                return ResolvedPlugin(adapter, manifest)
            }
        }
    }
    // docs:end adapter-mock-substitution

    @Test
    fun `an engine can be built with a mock adapter registry`() {
        val engine =
            ApapEngineBuilder()
                .adapterRegistry(mockAdapterRegistry(CapabilityId("chat")))
                .build()
        assertNotNull(engine.admin)
        engine.close()
    }
}
