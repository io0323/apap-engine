package apap.provider

import apap.adapter.spi.plugin.PluginManifest
import apap.domain.model.vo.ProviderId
import apap.domain.port.ProviderRepository

/**
 * テスト用の[AdapterRegistry]実装。
 *
 * ADR-0041で解決キーがproviderIdになったが、テストはpluginId単位でAdapterを用意する方が書きやすい。
 * そこで[providerRepository]でproviderId→`adapterPluginId`を翻訳する。
 * 本番でProviderごとのインスタンスを持つのは[ProviderAdapterProvisioner]の役目。
 */
class InMemoryAdapterRegistry(
    private val providerRepository: ProviderRepository? = null,
) : AdapterRegistry {
    private val resolved = mutableMapOf<String, ResolvedPlugin>()

    fun register(
        pluginId: String,
        manifest: PluginManifest,
        adapter: FakeProviderAdapter,
    ) {
        resolved[pluginId] = ResolvedPlugin(adapter, manifest)
    }

    override fun resolve(providerId: ProviderId): ResolvedPlugin {
        val pluginId =
            providerRepository?.findById(providerId)?.adapterPluginId
                ?: resolved.keys.singleOrNull()
                ?: throw PluginNotFoundException(providerId.value)
        return resolved[pluginId] ?: throw PluginNotFoundException(pluginId)
    }
}
