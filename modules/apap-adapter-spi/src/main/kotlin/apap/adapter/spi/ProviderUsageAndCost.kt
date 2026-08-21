package apap.adapter.spi

import apap.domain.model.vo.Cost
import apap.domain.model.vo.Period
import apap.domain.model.vo.Usage

/** 03_基本設計.md 3.3.2 `fetchUsage(period): ProviderUsage?`。Provider側API未提供のAdapterはnullを返す。 */
data class ProviderUsage(
    val period: Period,
    val usage: Usage,
)

/** 03_基本設計.md 3.3.2 `fetchCost(period): ProviderCost?`。Provider側API未提供のAdapterはnullを返す。 */
data class ProviderCost(
    val period: Period,
    val cost: Cost,
)
