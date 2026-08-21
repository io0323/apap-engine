package apap.domain.model.vo

import java.time.Instant

/** 04_ドメイン設計.md 4.4: from, to。from < to。 */
data class Period(
    val from: Instant,
    val to: Instant,
) {
    init {
        require(from.isBefore(to)) { "Period.from must be before Period.to: from=$from, to=$to" }
    }

    fun overlaps(other: Period): Boolean = from.isBefore(other.to) && other.from.isBefore(to)
}
