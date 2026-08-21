package apap.adapter.spi.plugin

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.SemVer

/**
 * 15_Provider追加手順.md 15.1 Step2 `plugin.yaml` のスキーマ。
 * `signature` は配布パッケージの署名文字列（検証自体はPlugin Manager/Infrastructure層の責務、
 * 16_拡張ポイント.md 16.1「署名検証」）。
 */
data class PluginManifest(
    val pluginId: String,
    val version: SemVer,
    val spiVersionRange: SemVerRange,
    val entryPoint: String,
    val capabilities: Set<CapabilityId>,
    val authTypes: Set<String>,
    val signature: String,
) {
    init {
        require(pluginId.isNotBlank()) { "plugin_id must not be blank" }
        require(entryPoint.isNotBlank()) { "entry_point must not be blank" }
        require(authTypes.isNotEmpty()) { "auth_types must not be empty" }
        require(signature.isNotBlank()) { "signature must not be blank" }
    }
}
