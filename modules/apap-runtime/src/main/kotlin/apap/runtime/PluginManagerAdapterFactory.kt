package apap.runtime

import apap.plugin.PluginManager
import apap.provider.PluginNotFoundException
import apap.provider.ProviderAdapterFactory
import apap.provider.ResolvedPlugin

/**
 * [PluginManager]を[ProviderAdapterFactory]へ橋渡しする（ADR-0041）。
 *
 * 以前はここが`AdapterRegistry`を実装し、`getAdapter(pluginId)`の**単一インスタンス**を
 * そのまま返していた。そのため1つのPluginを複数のProviderが参照すると同じインスタンスが
 * 共有され、しかも`initialize`はどこからも呼ばれていなかった。
 * 現在は呼ぶたびに新しいインスタンスを作り、初期化は[apap.provider.ProviderAdapterProvisioner]が行う。
 */
internal class PluginManagerAdapterFactory(
    private val pluginManager: PluginManager,
) : ProviderAdapterFactory {
    override fun instantiate(pluginId: String): ResolvedPlugin {
        val manifest = pluginManager.manifest(pluginId) ?: throw PluginNotFoundException(pluginId)
        return ResolvedPlugin(pluginManager.newAdapter(pluginId), manifest)
    }
}
