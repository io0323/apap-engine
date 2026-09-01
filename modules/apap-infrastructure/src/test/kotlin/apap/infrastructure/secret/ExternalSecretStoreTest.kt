package apap.infrastructure.secret

import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * `java.net.http.HttpClient`が実際にHTTPを話すことを、JDK同梱の`com.sun.net.httpserver.HttpServer`
 * （新規外部依存を追加しないテスト用の最小サーバ）を相手に検証する。
 */
class ExternalSecretStoreTest {
    private lateinit var server: HttpServer
    private val secrets = ConcurrentHashMap<String, String>()
    private val ref = CredentialRef("db-password", 1, CredentialState.ACTIVE)
    private var receivedAuthHeader: String? = null

    @BeforeEach
    fun setUp() {
        secrets.clear()
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/") { exchange ->
            receivedAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            val secretRef = exchange.requestURI.path.removePrefix("/")
            when (exchange.requestMethod) {
                "GET" -> {
                    val value = secrets[secretRef]
                    if (value == null) {
                        exchange.sendResponseHeaders(404, -1)
                    } else {
                        val bytes = value.toByteArray()
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                }
                "PUT" -> {
                    secrets[secretRef] = exchange.requestBody.readBytes().decodeToString()
                    exchange.sendResponseHeaders(204, -1)
                }
                else -> exchange.sendResponseHeaders(405, -1)
            }
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun store() =
        ExternalSecretStore(
            baseUrl = "http://localhost:${server.address.port}",
            authHeader = { "Authorization" to "Bearer test-token" },
        )

    @Test
    fun `store then resolve round-trips the value and sends the configured auth header`() {
        val secretStore = store()

        secretStore.store(ref, "s3cr3t".toCharArray())
        val resolved = secretStore.resolve(ref)

        assertEquals("s3cr3t", String(resolved))
        assertEquals("Bearer test-token", receivedAuthHeader)
    }

    @Test
    fun `resolve of an unregistered secret throws SecretNotFoundException`() {
        assertThrows(SecretNotFoundException::class.java) { store().resolve(ref) }
    }
}
