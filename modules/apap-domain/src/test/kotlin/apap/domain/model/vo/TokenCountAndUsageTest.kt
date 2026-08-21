package apap.domain.model.vo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TokenCountAndUsageTest {
    @Test
    fun `TokenCount rejects negative value`() {
        assertThrows(IllegalArgumentException::class.java) { TokenCount(-1) }
    }

    @Test
    fun `TokenCount plus sums values`() {
        assertEquals(TokenCount(30), TokenCount(10) + TokenCount(20))
    }

    @Test
    fun `Usage requires total equal to input plus output`() {
        assertThrows(IllegalArgumentException::class.java) {
            Usage(TokenCount(10), TokenCount(20), TokenCount(999))
        }
    }

    @Test
    fun `Usage of computes total automatically`() {
        val usage = Usage.of(TokenCount(10), TokenCount(20))
        assertEquals(TokenCount(30), usage.totalTokens)
        assertEquals(false, usage.estimated)
    }

    @Test
    fun `Usage carries estimated flag and optional token counts`() {
        val usage = Usage.of(TokenCount(1), TokenCount(2), estimated = true, cachedTokens = TokenCount(1))
        assertEquals(true, usage.estimated)
        assertEquals(TokenCount(1), usage.cachedTokens)
    }
}
