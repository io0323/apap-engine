package apap.domain.model.vo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ScoreTest {
    @Test
    fun `rejects value above 1_0`() {
        assertThrows(IllegalArgumentException::class.java) { Score(1.1) }
    }

    @Test
    fun `rejects negative value`() {
        assertThrows(IllegalArgumentException::class.java) { Score(-0.1) }
    }

    @Test
    fun `accepts boundary values`() {
        Score(0.0)
        Score(1.0)
    }

    @Test
    fun `coercedPlus clamps to the valid range instead of throwing`() {
        assertEquals(1.0, Score(0.98).coercedPlus(0.05).value, 1e-9)
        assertEquals(0.0, Score(0.02).coercedPlus(-0.5).value, 1e-9)
    }

    @Test
    fun `compares by value`() {
        assert(Score(0.2) < Score(0.5))
    }
}
