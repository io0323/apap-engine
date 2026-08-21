package apap.domain.model.cost

import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PriceBookTest {
    private val modelId = ModelId(testUlid('A'))

    private fun entry(
        from: Instant,
        to: Instant,
    ) = PriceEntry(
        modelId = modelId,
        inputPer1k = Money(BigDecimal("0.001"), "USD"),
        outputPer1k = Money(BigDecimal("0.002"), "USD"),
        period = Period(from, to),
    )

    @Test
    fun `accepts non overlapping entries for the same model`() {
        PriceBook(
            "pb1",
            listOf(
                entry(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z")),
                entry(Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z")),
            ),
        )
    }

    @Test
    fun `rejects overlapping entries for the same model`() {
        assertThrows(PriceBookOverlapException::class.java) {
            PriceBook(
                "pb1",
                listOf(
                    entry(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z")),
                    entry(Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z")),
                ),
            )
        }
    }

    @Test
    fun `addEntry re-validates the overlap invariant`() {
        val book =
            PriceBook(
                "pb1",
                listOf(entry(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"))),
            )
        assertThrows(PriceBookOverlapException::class.java) {
            book.addEntry(entry(Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z")))
        }
    }

    @Test
    fun `PriceEntry requires matching currency for input and output`() {
        assertThrows(IllegalArgumentException::class.java) {
            PriceEntry(
                modelId = modelId,
                inputPer1k = Money(BigDecimal("0.001"), "USD"),
                outputPer1k = Money(BigDecimal("0.002"), "JPY"),
                period = Period(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z")),
            )
        }
    }
}
