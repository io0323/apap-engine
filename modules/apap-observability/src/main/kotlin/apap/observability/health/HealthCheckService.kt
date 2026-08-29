package apap.observability.health

/**
 * 02_システム仕様.md 2.19 Health Check APIの3系統を集約する。
 * - [liveness]: プロセス生存（`/healthz`）。このコードが実行できている時点で常にUP。
 * - [readiness]: 依存ストア接続 + Plugin初期化完了（`/readyz`）。[readinessIndicators]の最悪状態を返す。
 *   JDBC実装（apap-infrastructure）/PluginManager（apap-plugin）は本タスク時点で未実装のため、
 *   既定の空リストでは判定対象がなくUPを返す。それらの実装が揃い次第、対応する[HealthIndicator]を
 *   注入すること（既知の未カバー範囲、MetricsEngineと同じ理由でここに明記する）。
 * - [providerHealth]: Provider別健全性の集約（`/health/providers`）。
 */
class HealthCheckService(
    private val readinessIndicators: List<HealthIndicator> = emptyList(),
    private val providerHealthIndicator: HealthIndicator = HealthIndicator { HealthCheckResult(HealthState.UP) },
) {
    fun liveness(): HealthCheckResult = HealthCheckResult(HealthState.UP)

    fun readiness(): HealthCheckResult = aggregate(readinessIndicators.map { it.check() })

    fun providerHealth(): HealthCheckResult = providerHealthIndicator.check()

    private fun aggregate(results: List<HealthCheckResult>): HealthCheckResult {
        if (results.isEmpty()) return HealthCheckResult(HealthState.UP)
        val worst = results.maxBy { severity(it.state) }.state
        val details = results.flatMap { it.details.entries }.associate { it.key to it.value }
        return HealthCheckResult(worst, details)
    }

    private fun severity(state: HealthState): Int =
        when (state) {
            HealthState.UP -> 0
            HealthState.DEGRADED -> 1
            HealthState.DOWN -> 2
        }
}
