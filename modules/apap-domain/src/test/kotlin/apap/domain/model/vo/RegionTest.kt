package apap.domain.model.vo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RegionTest {
    @Test
    fun `accepts a code present in the configured table`() {
        val table = RegionCodeTable(setOf("jp-east", "us-west"))
        assertEquals("jp-east", Region.of("jp-east", table).code)
    }

    @Test
    fun `rejects a code absent from the configured table`() {
        val table = RegionCodeTable(setOf("jp-east"))
        assertThrows(IllegalArgumentException::class.java) { Region.of("eu-west", table) }
    }

    @Test
    fun `table can be swapped in tests without hardcoded enum`() {
        val narrowTable = RegionCodeTable(setOf("only-region"))
        Region.of("only-region", narrowTable)
        assertThrows(IllegalArgumentException::class.java) { Region.of("jp-east", narrowTable) }
    }
}
