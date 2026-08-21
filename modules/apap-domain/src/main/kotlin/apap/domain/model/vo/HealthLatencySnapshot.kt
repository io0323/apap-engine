package apap.domain.model.vo

/**
 * 02_システム仕様.md 2.5.2: S_latency/S_avail算出に使う直近ウィンドウの集計値。
 * `apap.domain.port`パッケージはRepository/SPIのInterface置き場（実装クラス・data classを持ち込まない、
 * DomainPortInterfaceRuleTest）のため、この値オブジェクトはVOとして`apap.domain.model.vo`に置く。
 */
data class HealthLatencySnapshot(
    val successRate: Double,
    val p90LatencyMs: Long,
    val sampleCount: Int,
) {
    init {
        require(successRate in 0.0..1.0) { "successRate must be within 0.0..1.0: $successRate" }
        require(p90LatencyMs >= 0) { "p90LatencyMs must not be negative: $p90LatencyMs" }
        require(sampleCount >= 0) { "sampleCount must not be negative: $sampleCount" }
    }

    companion object {
        val EMPTY = HealthLatencySnapshot(successRate = 1.0, p90LatencyMs = 0, sampleCount = 0)
    }
}
