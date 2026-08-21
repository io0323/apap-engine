package apap.domain.model.cost

import apap.domain.model.IllegalStateTransitionException
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period

class PriceBookOverlapException(
    modelId: ModelId,
) : IllegalStateTransitionException("PriceBook has overlapping PriceEntry periods for model $modelId")

/**
 * 04_ドメイン設計.md 4.3.5 / 12_ER図.md PRICE_ENTRY。
 * ERの`effective_from`のみの表現に対し、ドメインでは重複検証のため[Period]（from/to）を用いる
 * （Infrastructure層での永続化方法はこの表現から独立して選べる）。
 */
data class PriceEntry(
    val modelId: ModelId,
    val inputPer1k: Money,
    val outputPer1k: Money,
    val period: Period,
) {
    init {
        require(inputPer1k.currency == outputPer1k.currency) {
            "inputPer1k and outputPer1k must share the same currency: " +
                "${inputPer1k.currency} vs ${outputPer1k.currency}"
        }
    }
}

/** 04_ドメイン設計.md 4.3.5 PriceBook Aggregate（Root）: 期間重複エントリ不可（同一modelId内）。 */
data class PriceBook(
    val priceBookId: String,
    val entries: List<PriceEntry> = emptyList(),
) {
    init {
        entries
            .groupBy { it.modelId }
            .forEach { (modelId, entriesForModel) ->
                for (i in entriesForModel.indices) {
                    for (j in i + 1 until entriesForModel.size) {
                        if (entriesForModel[i].period.overlaps(entriesForModel[j].period)) {
                            throw PriceBookOverlapException(modelId)
                        }
                    }
                }
            }
    }

    fun addEntry(entry: PriceEntry): PriceBook = copy(entries = entries + entry)
}
