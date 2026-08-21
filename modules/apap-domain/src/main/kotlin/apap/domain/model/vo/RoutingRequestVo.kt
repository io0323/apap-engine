package apap.domain.model.vo

/** 02_システム仕様.md 2.5.2: `preferences.optimize_for` の4値。重み既定値もこれに紐づく。 */
enum class OptimizeFor { COST, LATENCY, QUALITY, BALANCED }

/** 02_システム仕様.md 2.5.1: RoutingRequest.constraints。 */
data class RoutingConstraints(
    val region: Region? = null,
    val maxCost: Money? = null,
    val maxLatencyMs: Long? = null,
    val excludeProviders: Set<ProviderId> = emptySet(),
) {
    init {
        maxLatencyMs?.let { require(it > 0) { "maxLatencyMs must be positive: $it" } }
    }
}

/** 02_システム仕様.md 2.5.1: RoutingRequest.preferences。 */
data class RoutingPreferences(
    val optimizeFor: OptimizeFor = OptimizeFor.BALANCED,
)
