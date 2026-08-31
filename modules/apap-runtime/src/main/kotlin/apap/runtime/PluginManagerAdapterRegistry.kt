package apap.runtime

import apap.plugin.PluginManager
import apap.provider.AdapterRegistry
import apap.provider.PluginNotFoundException
import apap.provider.ResolvedPlugin

/**
 * [apap.provider.AdapterRegistry]（`ProviderManager`のVALIDATING処理が要求する最小限の口）と
 * [PluginManager]（実際のPluginロード/アンロードを担う、`apap-plugin`モジュール）を橋渡しする。
 * `apap-plugin`は`apap-domain`+`apap-adapter-spi`のみに依存する方針（PluginManagerのKDoc参照）のため、
 * この橋渡し自体は両方に依存できる`apap-runtime`（コンポジションルート）に置く。
 */
internal class PluginManagerAdapterRegistry(
    private val pluginManager: PluginManager,
) : AdapterRegistry {
    override fun resolve(pluginId: String): ResolvedPlugin {
        val manifest = pluginManager.manifest(pluginId) ?: throw PluginNotFoundException(pluginId)
        return ResolvedPlugin(pluginManager.getAdapter(pluginId), manifest)
    }
}
