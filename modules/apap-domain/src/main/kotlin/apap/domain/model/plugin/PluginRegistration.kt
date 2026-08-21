package apap.domain.model.plugin

import apap.domain.model.IllegalStateTransitionException
import apap.domain.model.vo.SemVer

enum class PluginRegistrationStatus { LOADED, UNLOADED, QUARANTINED }

class PluginSignatureNotVerifiedException(
    pluginId: String,
) : IllegalStateTransitionException("PluginRegistration $pluginId cannot be LOADED without a verified signature")

/**
 * 04_ドメイン設計.md 4.3.5 PluginRegistration Aggregate（Root）。
 * 不変条件: 署名検証合格が必要（[load]は`signatureVerified=false`の場合に必ず例外を投げる）。
 * 実際の署名検証（暗号処理）はI/Oを伴うため、検証結果を`signatureVerified`として
 * 呼び出し側（Plugin Manager）から供給する。
 */
data class PluginRegistration(
    val pluginId: String,
    val version: SemVer,
    val spiVersion: SemVer,
    val signature: String,
    val signatureVerified: Boolean = false,
    val status: PluginRegistrationStatus = PluginRegistrationStatus.UNLOADED,
) {
    init {
        require(signature.isNotBlank()) { "signature must not be blank" }
    }

    fun load(): PluginRegistration {
        if (!signatureVerified) {
            throw PluginSignatureNotVerifiedException(pluginId)
        }
        return copy(status = PluginRegistrationStatus.LOADED)
    }

    fun unload(): PluginRegistration = copy(status = PluginRegistrationStatus.UNLOADED)

    fun quarantine(): PluginRegistration = copy(status = PluginRegistrationStatus.QUARANTINED)
}
