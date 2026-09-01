package apap.infrastructure.secret

import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EnvVarSecretStoreTest {
    private val ref = CredentialRef("MY_API_KEY", 1, CredentialState.ACTIVE)

    @Test
    fun `resolve reads the named environment variable`() {
        val store = EnvVarSecretStore(env = mapOf("MY_API_KEY" to "sk-12345"))

        assertEquals("sk-12345", String(store.resolve(ref)))
    }

    @Test
    fun `resolve throws SecretNotFoundException when the variable is not set`() {
        val store = EnvVarSecretStore(env = emptyMap())

        assertThrows(SecretNotFoundException::class.java) { store.resolve(ref) }
    }

    @Test
    fun `store is unsupported (environment variables cannot be set from within the running JVM)`() {
        val store = EnvVarSecretStore(env = emptyMap())

        assertThrows(UnsupportedOperationException::class.java) { store.store(ref, "value".toCharArray()) }
    }
}
