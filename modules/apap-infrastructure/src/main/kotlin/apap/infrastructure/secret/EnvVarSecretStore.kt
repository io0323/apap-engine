package apap.infrastructure.secret

import apap.domain.model.vo.CredentialRef
import apap.domain.port.SecretStore

class SecretNotFoundException(
    secretRef: String,
) : NoSuchElementException("No secret registered for reference: $secretRef")

/**
 * [SecretStore]の環境変数版実装（ADR-0002: 開発・テスト用、既定）。[CredentialRef.secretRef]を
 * 環境変数名としてそのまま使う。
 *
 * 環境変数はJVMプロセスの起動後に書き換えられないため（`System.getenv()`はプロセス起動時点の
 * スナップショット）、[store]は非対応（[UnsupportedOperationException]）。開発・テスト用途では
 * 環境変数自体をプロセス起動前に設定する運用を前提とする。
 */
class EnvVarSecretStore(
    private val env: Map<String, String> = System.getenv(),
) : SecretStore {
    override fun resolve(ref: CredentialRef): CharArray {
        val value = env[ref.secretRef] ?: throw SecretNotFoundException(ref.secretRef)
        return value.toCharArray()
    }

    override fun store(
        ref: CredentialRef,
        value: CharArray,
    ): Nothing =
        throw UnsupportedOperationException(
            "EnvVarSecretStore is read-only (environment variables cannot be set from within the running JVM); " +
                "set the '${ref.secretRef}' environment variable before process startup instead",
        )
}
