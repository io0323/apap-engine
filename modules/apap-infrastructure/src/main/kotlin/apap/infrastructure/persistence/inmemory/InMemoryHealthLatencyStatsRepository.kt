package apap.infrastructure.persistence.inmemory

import apap.domain.model.vo.HealthLatencySnapshot
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.port.HealthLatencyStatsRepository
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.ceil

private data class Outcome(
    val success: Boolean,
    val latencyMs: Long,
    val at: Instant,
)

/**
 * [HealthLatencyStatsRepository]の本番用In-Memory実装（ADR-0001: 高頻度参照データはRDBMSを想定しない）。
 * [recordOutcome]で積んだ生の観測値から、[snapshot]呼出時にウィンドウ内の成功率とp50/p90レイテンシを集計する。
 */
class InMemoryHealthLatencyStatsRepository : HealthLatencyStatsRepository {
    private val outcomes = ConcurrentHashMap<Pair<ProviderId, ModelId>, CopyOnWriteArrayList<Outcome>>()

    override fun recordOutcome(
        providerId: ProviderId,
        modelId: ModelId,
        success: Boolean,
        latencyMs: Long,
        at: Instant,
    ) {
        outcomes.computeIfAbsent(providerId to modelId) { CopyOnWriteArrayList() }.add(Outcome(success, latencyMs, at))
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
        return HealthLatencySnapshot(
            successRate = successRate,
            p50LatencyMs = sortedLatencies[quantileIndex(P50_QUANTILE, sortedLatencies.size)],
            p90LatencyMs = sortedLatencies[quantileIndex(P90_QUANTILE, sortedLatencies.size)],
            sampleCount = inWindow.size,
        )
    }

    private fun quantileIndex(
        quantile: Double,
        size: Int,
    ): Int = (ceil(quantile * size).toInt() - 1).coerceIn(0, size - 1)

    companion object {
        private const val P50_QUANTILE = 0.5
        private const val P90_QUANTILE = 0.9
    }
}
