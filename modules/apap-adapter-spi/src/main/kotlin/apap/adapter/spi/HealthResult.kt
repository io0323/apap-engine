package apap.adapter.spi

import apap.domain.model.provider.ProviderHealthStatus
import java.time.Duration

/** 03_基本設計.md 3.3.2 `healthCheck(): HealthResult`。status/latency/detailの3項目（設計書コメント通り）。 */
data class HealthResult(
    val status: ProviderHealthStatus,
    val latency: Duration,
    val detail: String? = null,
) {
    init {
        require(!latency.isNegative) { "HealthResult.latency must not be negative: $latency" }
    }
}
