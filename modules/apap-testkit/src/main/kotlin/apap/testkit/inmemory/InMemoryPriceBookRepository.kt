package apap.testkit.inmemory

import apap.domain.model.cost.PriceBook
import apap.domain.model.cost.PriceEntry
import apap.domain.model.vo.ModelId
import apap.domain.port.PriceBookRepository
import java.time.Instant

class InMemoryPriceBookRepository : PriceBookRepository {
    private val priceBooks = mutableMapOf<String, PriceBook>()

    override fun findById(priceBookId: String): PriceBook? = priceBooks[priceBookId]

    override fun save(priceBook: PriceBook) {
        priceBooks[priceBook.priceBookId] = priceBook
    }

    override fun findCurrentEntry(
        modelId: ModelId,
        now: Instant,
    ): PriceEntry? =
        priceBooks.values
            .asSequence()
            .flatMap { it.entries }
            .filter { it.modelId == modelId }
            .firstOrNull { !it.period.from.isAfter(now) && it.period.to.isAfter(now) }
}
