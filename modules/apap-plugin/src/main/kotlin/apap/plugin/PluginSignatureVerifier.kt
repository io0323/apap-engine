package apap.plugin

import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * ADR-0025: `plugin.yaml`の`signature`フィールド（Base64エンコードされた署名値）をJDK標準の
 * `java.security.Signature`（RSA/ECDSA、[algorithm]で選択）のみで検証する。新規外部依存を
 * 追加しない（`PluginManifestParser`が独自YAMLパーサを自前実装した際の判断基準と同じ精神）。
 *
 * 検証対象は配布パッケージ（Plugin jarそのもの）のバイト列。鍵配布・ローテーション方式は
 * 本クラスの範囲外（ADR-0025「未決定」参照、運用手順として別途整備する）。
 */
class PluginSignatureVerifier(
    private val trustedPublicKey: PublicKey,
    private val algorithm: String = DEFAULT_ALGORITHM,
) {
    fun verify(
        artifactBytes: ByteArray,
        base64Signature: String,
    ): Boolean {
        val signatureBytes = runCatching { Base64.getDecoder().decode(base64Signature) }.getOrNull() ?: return false
        return runCatching {
            Signature
                .getInstance(algorithm)
                .apply {
                    initVerify(trustedPublicKey)
                    update(artifactBytes)
                }.verify(signatureBytes)
        }.getOrDefault(false)
    }

    private companion object {
        const val DEFAULT_ALGORITHM = "SHA256withRSA"
    }
}
