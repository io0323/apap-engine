package apap.domain.port

import apap.domain.model.vo.HealthLatencySnapshot
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import java.time.Duration
import java.time.Instant

/**
 * 02_システム仕様.md 2.5.2: スコアリングに必要な「直近5分の成功率とp90レイテンシ」の保持口。
 * ADR-0001に従い、この種の高頻度参照データはRDBMSを想定しない実装（In-Memory/分散KVS等）とする。
 * 判定ロジック（このデータをどうスコアへ変換するか）はRoutingDomainServiceに委譲し、
 * 本Portは生の観測値の記録・集計取得のみを担う。
 */
interface HealthLatencyStatsRepository {
    fun recordOutcome(
        providerId: ProviderId,
        modelId: ModelId,
        success: Boolean,
        latencyMs: Long,
        at: Instant,
    )

    fun snapshot(
        providerId: ProviderId,
        modelId: ModelId,
        now: Instant,
        window: Duration = DEFAULT_WINDOW,
    ): HealthLatencySnapshot
}

// トップレベル定数として置く: interfaceにcompanion objectを持たせるとDomainPortInterfaceRuleTest
// （apap.domain.portパッケージにはinterfaceのみを置く）に抵触するため。
private const val DEFAULT_WINDOW_MINUTES = 5L
private val DEFAULT_WINDOW: Duration = Duration.ofMinutes(DEFAULT_WINDOW_MINUTES)
