package apap.observability.health

/**
 * 02_システム仕様.md 2.19 Health Check（`/healthz` Liveness、`/readyz` Readiness、
 * `/health/providers` Provider Health集約）の判定結果。実HTTPエンドポイント自体は
 * `apap-gateway`（P10）の責務であり、本モジュールは判定ロジックとその結果までを担う。
 */
enum class HealthState { UP, DEGRADED, DOWN }

data class HealthCheckResult(
    val state: HealthState,
    val details: Map<String, String> = emptyMap(),
)

/**
 * 16_拡張ポイント.md 16.6「ヘルス判定」拡張点。個々の依存（DB接続、Plugin初期化完了等）の
 * 健全性判定を差し替え可能にする。
 */
fun interface HealthIndicator {
    fun check(): HealthCheckResult
}
