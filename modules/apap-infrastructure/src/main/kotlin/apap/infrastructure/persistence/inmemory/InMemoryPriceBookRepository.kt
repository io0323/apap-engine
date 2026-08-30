package apap.infrastructure.persistence.inmemory

import apap.domain.model.cost.PriceBook
import apap.domain.model.cost.PriceEntry
import apap.domain.model.vo.ModelId
import apap.domain.port.PriceBookRepository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** [PriceBookRepository]の本番用In-Memory実装。 */
class InMemoryPriceBookRepository : PriceBookRepository {
    private val priceBooks = ConcurrentHashMap<String, PriceBook>()

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
