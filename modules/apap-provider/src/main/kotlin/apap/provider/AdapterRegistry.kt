package apap.provider

import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.plugin.PluginManifest

/**
 * 03_基本設計.md 3.3.6 `PluginManager.get(pluginId): ProviderAdapter` に相当する、
 * pluginIdからAdapter実装とマニフェストを引くための最小限の口。
 *
 * フルのPlugin Manager（scan・署名検証・ロード/アンロード・隔離、16_拡張ポイント.md 16.1）は
 * apap-pluginモジュールの責務であり本モジュールの対象外。ここでは
 * [apap.provider.ProviderManager] のVALIDATING処理（Credential検証・疎通・Capability申告の突合）が
 * 必要とする「既にロード済のAdapter実装とそのマニフェストを引く」部分だけを切り出す。
 */
interface AdapterRegistry {
    fun resolve(pluginId: String): ResolvedPlugin
}

data class ResolvedPlugin(
    val adapter: ProviderAdapter,
    val manifest: PluginManifest,
)

class PluginNotFoundException(
    pluginId: String,
) : Exception("No loaded plugin found for pluginId: $pluginId")
