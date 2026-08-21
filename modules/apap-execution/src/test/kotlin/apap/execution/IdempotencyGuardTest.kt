package apap.execution

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** 02_システム仕様.md 2.11「冪等キーで二重実行防止」/ NFR-AVL-003。 */
class IdempotencyGuardTest {
    @Test
    fun `claiming the same key twice while in-flight is rejected`() {
        val guard = IdempotencyGuard()
        guard.claim("tenant-1:key-1")
        assertThrows(DuplicateRequestException::class.java) { guard.claim("tenant-1:key-1") }
    }

    @Test
    fun `releasing allows the same key to be claimed again`() {
        val guard = IdempotencyGuard()
        guard.claim("tenant-1:key-1")
        guard.release("tenant-1:key-1")
        guard.claim("tenant-1:key-1")
    }

    @Test
    fun `different keys do not interfere with each other`() {
        val guard = IdempotencyGuard()
        guard.claim("tenant-1:key-1")
        guard.claim("tenant-1:key-2")
        guard.claim("tenant-2:key-1")
    }

    @Test
    fun `null key is a no-op (requests without an idempotency key are never deduplicated)`() {
        val guard = IdempotencyGuard()
        guard.claim(null)
        guard.claim(null)
        guard.release(null)
    }
}
