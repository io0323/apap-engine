package apap.testkit.inmemory

import apap.domain.model.vo.HealthLatencySnapshot
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.port.HealthLatencyStatsRepository
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

private data class Outcome(
    val success: Boolean,
    val latencyMs: Long,
    val at: Instant,
)

/** [recordOutcome]で積んだ生の観測値から、[snapshot]呼出時にウィンドウ内の成功率とp90レイテンシを集計する。 */
class InMemoryHealthLatencyStatsRepository : HealthLatencyStatsRepository {
    private val outcomes = mutableMapOf<Pair<ProviderId, ModelId>, MutableList<Outcome>>()

    override fun recordOutcome(
        providerId: ProviderId,
        modelId: ModelId,
        success: Boolean,
        latencyMs: Long,
        at: Instant,
    ) {
        outcomes.getOrPut(providerId to modelId) { mutableListOf() }.add(Outcome(success, latencyMs, at))
    }

    override fun snapshot(
        providerId: ProviderId,
        modelId: ModelId,
        now: Instant,
        window: Duration,
    ): HealthLatencySnapshot {
        val windowStart = now.minus(window)
        val inWindow =
            outcomes[providerId to modelId].orEmpty().filter { !it.at.isBefore(windowStart) && !it.at.isAfter(now) }
        if (inWindow.isEmpty()) return HealthLatencySnapshot.EMPTY

        val successRate = inWindow.count { it.success }.toDouble() / inWindow.size
        val sortedLatencies = inWindow.map { it.latencyMs }.sorted()
        val p90Index = (ceil(P90_QUANTILE * sortedLatencies.size).toInt() - 1).coerceIn(0, sortedLatencies.size - 1)
        return HealthLatencySnapshot(
            successRate = successRate,
            p90LatencyMs = sortedLatencies[p90Index],
            sampleCount = inWindow.size,
        )
    }

    companion object {
        private const val P90_QUANTILE = 0.9
    }
}
