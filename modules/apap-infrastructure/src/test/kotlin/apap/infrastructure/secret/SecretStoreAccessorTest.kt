package apap.infrastructure.secret

import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SecretStoreAccessorTest {
    @Test
    fun `resolve delegates to the underlying SecretStore and wraps the result in a SecretValue`() {
        val ref = CredentialRef("api-key", 1, CredentialState.ACTIVE)
        val secretStore = EnvVarSecretStore(env = mapOf("api-key" to "sk-live-1234"))
        val accessor = SecretStoreAccessor(secretStore)

        accessor.resolve(ref).use { value ->
            assertEquals("sk-live-1234", String(value.charArray()))
        }
    }
}
