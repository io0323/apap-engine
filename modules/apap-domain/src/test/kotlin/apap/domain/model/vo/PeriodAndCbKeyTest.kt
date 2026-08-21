package apap.domain.model.vo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class PeriodAndCbKeyTest {
    @Test
    fun `rejects from not before to`() {
        val t = Instant.parse("2026-01-01T00:00:00Z")
        assertThrows(IllegalArgumentException::class.java) { Period(t, t) }
        assertThrows(IllegalArgumentException::class.java) { Period(t.plusSeconds(1), t) }
    }

    @Test
    fun `detects overlapping periods`() {
        val p1 = Period(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"))
        val p2 = Period(Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z"))
        assertTrue(p1.overlaps(p2))
    }

    @Test
    fun `non overlapping periods do not overlap`() {
        val p1 = Period(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"))
        val p2 = Period(Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z"))
        assertFalse(p1.overlaps(p2))
    }

    @Test
    fun `a period strictly after another does not overlap (short-circuits before the second check)`() {
        val earlier = Period(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"))
        val later = Period(Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z"))
        assertFalse(later.overlaps(earlier))
    }

    @Test
    fun `CbKey is a plain value pairing of providerId and modelId`() {
        val key = CbKey(ProviderId(testUlid('A')), ModelId(testUlid('B')))
        assertEquals(ProviderId(testUlid('A')), key.providerId)
        assertEquals(ModelId(testUlid('B')), key.modelId)
    }
}
