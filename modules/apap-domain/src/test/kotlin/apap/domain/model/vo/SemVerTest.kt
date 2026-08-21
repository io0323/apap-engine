package apap.domain.model.vo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemVerTest {
    @Test
    fun `parses core version`() {
        val v = SemVer.parse("1.2.3")
        assertEquals(SemVer(1, 2, 3), v)
    }

    @Test
    fun `parses pre-release and build metadata`() {
        val v = SemVer.parse("1.2.3-alpha.1+build.5")
        assertEquals(SemVer(1, 2, 3, "alpha.1", "build.5"), v)
    }

    @Test
    fun `toString round-trips`() {
        assertEquals("1.2.3-alpha.1+build.5", SemVer.parse("1.2.3-alpha.1+build.5").toString())
        assertEquals("1.0.0", SemVer.parse("1.0.0").toString())
    }

    @Test
    fun `toString renders pre-release-only and build-only forms`() {
        assertEquals("1.0.0-alpha", SemVer.parse("1.0.0-alpha").toString())
        assertEquals("1.0.0+build.5", SemVer.parse("1.0.0+build.5").toString())
    }

    @Test
    fun `rejects invalid format`() {
        assertThrows(IllegalArgumentException::class.java) { SemVer.parse("1.2") }
        assertThrows(IllegalArgumentException::class.java) { SemVer.parse("v1.2.3") }
    }

    @Test
    fun `rejects negative components constructed directly (bypassing parse)`() {
        assertThrows(IllegalArgumentException::class.java) { SemVer(-1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { SemVer(0, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SemVer(0, 0, -1) }
    }

    @Test
    fun `rejects malformed pre-release or build metadata constructed directly (bypassing parse)`() {
        assertThrows(IllegalArgumentException::class.java) { SemVer(1, 0, 0, preRelease = " has space") }
        assertThrows(IllegalArgumentException::class.java) { SemVer(1, 0, 0, build = " has space") }
    }

    @Test
    fun `is compatible when major version matches`() {
        assertTrue(SemVer(1, 0, 0).isCompatibleWith(SemVer(1, 9, 9)))
        assertFalse(SemVer(1, 0, 0).isCompatibleWith(SemVer(2, 0, 0)))
    }

    @Test
    fun `orders by major minor patch then pre-release precedence`() {
        assertTrue(SemVer(1, 0, 0) > SemVer(1, 0, 0, "alpha"))
        assertTrue(SemVer(1, 0, 0, "alpha") < SemVer(1, 0, 0, "alpha.1"))
        assertTrue(SemVer(1, 0, 0, "alpha.1") < SemVer(1, 0, 0, "alpha.beta"))
        assertTrue(SemVer(1, 0, 0, "1") < SemVer(1, 0, 0, "2"))
        assertTrue(SemVer(2, 0, 0) > SemVer(1, 9, 9))
    }

    @Test
    fun `two identical versions with no pre-release compare equal`() {
        assertTrue(SemVer(1, 0, 0).compareTo(SemVer(1, 0, 0)) == 0)
    }

    @Test
    fun `a pre-release version is lower precedence than the same version without one`() {
        assertTrue(SemVer(1, 0, 0, "alpha") < SemVer(1, 0, 0))
    }

    @Test
    fun `an alphanumeric identifier outranks a numeric identifier at the same position`() {
        assertTrue(SemVer(1, 0, 0, "beta") > SemVer(1, 0, 0, "1"))
    }
}
