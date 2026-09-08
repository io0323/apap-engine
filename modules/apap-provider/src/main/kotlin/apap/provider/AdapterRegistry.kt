package apap.provider

import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.plugin.PluginManifest
import apap.domain.model.vo.ProviderId

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
    /**
     * そのProvider専用のAdapterインスタンスを返す。
     *
     * **キーはproviderIdであってpluginIdではない**（ADR-0041）。Providerは
     * `endpoints`/`credentialRefs`/`rateLimits`/`regions`をProviderごとに持つため、
     * pluginIdで引くと1インスタンスが複数Providerに共有され、どの設定で動くかが決まらない。
     * FR-SEC-005（Provider Isolation）とNFR-SEC-004（自ProviderのCredentialのみ）も
     * 共有インスタンスでは成立しない。生成と初期化は[ProviderAdapterProvisioner]が行う。
     */
    fun resolve(providerId: ProviderId): ResolvedPlugin
}

/**
 * Pluginから**新しい**Adapterインスタンスを作る。Plugin（ロード済みクラス・ClassLoader）は
 * 共有したまま、インスタンスだけをProviderごとに分けるための継ぎ目（ADR-0041）。
 */
interface ProviderAdapterFactory {
    /** 呼ぶたびに新しいインスタンスを返すこと（使い回すとProvider間で設定が混線する）。 */
    fun instantiate(pluginId: String): ResolvedPlugin
}

data class ResolvedPlugin(
    val adapter: ProviderAdapter,
    val manifest: PluginManifest,
)

class PluginNotFoundException(
    pluginId: String,
) : Exception("No loaded plugin found for pluginId: $pluginId")
