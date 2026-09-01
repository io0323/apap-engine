package apap.infrastructure.secret

import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.domain.model.vo.CredentialRef
import apap.domain.port.SecretStore

/**
 * [SecretStore]（apap-domain、広い責務——読み書き）を、Adapter実行時に渡す狭いView
 * [SecretAccessor]（apap-adapter-spi、読み取り専用、`SecretValue`の自動ゼロ埋めスコープ付き）へ
 * ブリッジする。apap-domainはapap-adapter-spiに依存できない（依存ゼロ原則）ため、このブリッジは
 * 両方に依存できるInfrastructure層（apap-infrastructure）に置く。
 */
class SecretStoreAccessor(
    private val secretStore: SecretStore,
) : SecretAccessor {
    override fun resolve(ref: CredentialRef): SecretValue = SecretValue(secretStore.resolve(ref))
}
