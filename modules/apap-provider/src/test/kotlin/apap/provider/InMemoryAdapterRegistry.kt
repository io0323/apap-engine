package apap.provider

import apap.adapter.spi.plugin.PluginManifest

/** テスト用の[AdapterRegistry]実装。[register]でpluginIdごとに[ResolvedPlugin]を登録する。 */
class InMemoryAdapterRegistry : AdapterRegistry {
    private val resolved = mutableMapOf<String, ResolvedPlugin>()

    fun register(
        pluginId: String,
        manifest: PluginManifest,
        adapter: FakeProviderAdapter,
    ) {
        resolved[pluginId] = ResolvedPlugin(adapter, manifest)
    }

    override fun resolve(id: String): ResolvedPlugin = resolved[id] ?: throw PluginNotFoundException(id)
}
