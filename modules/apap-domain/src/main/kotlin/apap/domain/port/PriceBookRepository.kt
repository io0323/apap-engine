package apap.domain.port

import apap.domain.model.cost.PriceBook
import apap.domain.model.cost.PriceEntry
import apap.domain.model.vo.ModelId
import java.time.Instant

/**
 * `PriceBook`（04_ドメイン設計.md 4.3.5 / 12_ER図.md PRICE_ENTRY）のRepository。3.4の
 * Repository一覧には無い（P1-P6当時はCost Engine自体が未着手だったため）。FR-OBS-005
 * （単価表管理）の実装に必要なため追加する。
 */
interface PriceBookRepository {
    fun findById(priceBookId: String): PriceBook?

    fun save(priceBook: PriceBook)

    /** [modelId]について[now]時点で有効な[PriceEntry]を返す（複数PriceBookに跨っていても横断的に探す）。 */
    fun findCurrentEntry(
        modelId: ModelId,
        now: Instant,
    ): PriceEntry?
}
