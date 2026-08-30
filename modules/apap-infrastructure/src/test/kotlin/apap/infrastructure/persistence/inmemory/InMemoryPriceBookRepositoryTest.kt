package apap.infrastructure.persistence.inmemory

import apap.domain.model.cost.PriceBook
import apap.domain.model.cost.PriceEntry
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class InMemoryPriceBookRepositoryTest {
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA0")

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
    fun `saves and finds a price book by id`() {
        val repo = InMemoryPriceBookRepository()
        val book = PriceBook("pb1", listOf(entry(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"))))
        repo.save(book)

        assertEquals(book, repo.findById("pb1"))
        assertNull(repo.findById("no-such-book"))
    }

    @Test
    fun `findCurrentEntry returns the entry covering the given instant`() {
        val repo = InMemoryPriceBookRepository()
        repo.save(
            PriceBook("pb1", listOf(entry(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z")))),
        )

        val found = repo.findCurrentEntry(modelId, Instant.parse("2026-01-15T00:00:00Z"))
        assertEquals(modelId, found?.modelId)
        assertNull(repo.findCurrentEntry(modelId, Instant.parse("2026-03-01T00:00:00Z")))
    }
}
