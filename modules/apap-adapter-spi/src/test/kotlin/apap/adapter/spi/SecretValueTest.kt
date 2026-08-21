package apap.adapter.spi

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SecretValueTest {
    @Test
    fun `charArray exposes the wrapped value before close`() {
        val secret = SecretValue("s3cr3t".toCharArray())
        assertArrayEquals("s3cr3t".toCharArray(), secret.charArray())
    }

    @Test
    fun `use zero-fills the internal buffer once the block completes`() {
        val original = "s3cr3t".toCharArray()
        val secret = SecretValue(original)

        secret.use { value -> assertArrayEquals("s3cr3t".toCharArray(), value.charArray()) }

        assertThrows(IllegalStateException::class.java) { secret.charArray() }
    }

    @Test
    fun `close is idempotent`() {
        val secret = SecretValue("s3cr3t".toCharArray())
        secret.close()
        secret.close()
        assertThrows(IllegalStateException::class.java) { secret.charArray() }
    }

    @Test
    fun `mutating the caller's original array after construction does not affect the stored value`() {
        val original = "s3cr3t".toCharArray()
        val secret = SecretValue(original)
        original.fill('x')
        assertArrayEquals("s3cr3t".toCharArray(), secret.charArray())
    }

    @Test
    fun `toString never reveals the secret value`() {
        val secret = SecretValue("s3cr3t".toCharArray())
        assert(!secret.toString().contains("s3cr3t"))
    }
}
