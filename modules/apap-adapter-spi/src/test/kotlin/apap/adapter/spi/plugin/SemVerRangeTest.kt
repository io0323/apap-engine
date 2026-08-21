package apap.adapter.spi.plugin

import apap.domain.model.vo.SemVer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemVerRangeTest {
    @Test
    fun `parses a single comparator`() {
        val range = SemVerRange.parse(">=1.2.0")
        assertTrue(range.contains(SemVer(1, 2, 0)))
        assertTrue(range.contains(SemVer(1, 9, 0)))
        assertFalse(range.contains(SemVer(1, 1, 9)))
    }

    @Test
    fun `parses a space separated AND range covering both bounds`() {
        val range = SemVerRange.parse(">=1.2 <2.0")
        assertFalse(range.contains(SemVer(1, 1, 9)))
        assertTrue(range.contains(SemVer(1, 2, 0)))
        assertTrue(range.contains(SemVer(1, 9, 9)))
        assertFalse(range.contains(SemVer(2, 0, 0)))
    }

    @Test
    fun `distinguishes gte and gt at the boundary`() {
        val inclusive = SemVerRange.parse(">=1.0.0")
        val exclusive = SemVerRange.parse(">1.0.0")
        assertTrue(inclusive.contains(SemVer(1, 0, 0)))
        assertFalse(exclusive.contains(SemVer(1, 0, 0)))
    }

    @Test
    fun `distinguishes lte and lt at the boundary`() {
        val inclusive = SemVerRange.parse("<=1.0.0")
        val exclusive = SemVerRange.parse("<1.0.0")
        assertTrue(inclusive.contains(SemVer(1, 0, 0)))
        assertFalse(exclusive.contains(SemVer(1, 0, 0)))
    }

    @Test
    fun `eq matches only the exact version`() {
        val range = SemVerRange.parse("=1.2.3")
        assertTrue(range.contains(SemVer(1, 2, 3)))
        assertFalse(range.contains(SemVer(1, 2, 4)))
    }

    @Test
    fun `rejects a comparator with no recognizable operator`() {
        assertThrows(IllegalArgumentException::class.java) { SemVerRange.parse("1.2.0") }
    }

    @Test
    fun `rejects an empty comparator list`() {
        assertThrows(IllegalArgumentException::class.java) { SemVerRange(emptyList()) }
    }
}
