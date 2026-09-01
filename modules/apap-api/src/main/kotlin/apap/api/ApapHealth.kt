package apap.api

/** 02_システム仕様.md 2.19 Health Check APIの3系統（Liveness/Readiness/Provider Health）の判定結果。 */
enum class ApapHealthState { UP, DEGRADED, DOWN }

data class ApapHealthResult(
    val state: ApapHealthState,
    val details: Map<String, String> = emptyMap(),
)

/**
 * [apap.runtime.ApapEngine.health]の公開面。実HTTPエンドポイント（`/healthz`等）自体は
 * 埋込ホスト（またはP10 `apap-gateway`）の責務であり、本インターフェースは判定結果までを提供する。
 */
interface ApapHealth {
    fun liveness(): ApapHealthResult

    fun readiness(): ApapHealthResult

    fun providerHealth(): ApapHealthResult
}
