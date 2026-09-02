package apap.gateway.routes

import apap.api.ApapHealthState
import apap.gateway.metrics.OpenMetricsRenderer
import apap.runtime.ApapEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * 本タスク指示7: `/healthz`, `/readyz`, `/metrics`。
 *
 * これらは**認証不要**にする（Kubernetesのprobeとメトリクススクレイプが対象であり、
 * 11_デプロイメント図.mdの構成ではクラスタ内部からのみ到達する）。
 * 認証を掛けるとprobeにトークンを持たせる必要が生じ、運用が壊れる。
 */
fun Route.opsRoutes(
    engine: ApapEngine,
    metricsRenderer: OpenMetricsRenderer,
) {
    // Liveness: プロセスが生きているか。依存先の状態では落とさない
    // （落とすと下流の一時障害でPodが再起動され、状況が悪化する）。
    get("/healthz") {
        val result = engine.health.liveness()
        call.respond(result.toStatus(), mapOf("state" to result.state.name, "details" to result.details))
    }

    // Readiness: トラフィックを受けられるか。DOWNなら503でLBから外す。
    get("/readyz") {
        val result = engine.health.readiness()
        call.respond(result.toStatus(), mapOf("state" to result.state.name, "details" to result.details))
    }

    get("/metrics") {
        call.respondText(metricsRenderer.render(), OPENMETRICS_CONTENT_TYPE)
    }
}

/**
 * [ApapHealthState] → HTTPステータス。DEGRADEDは200のまま
 * （縮退しつつも要求は処理できる状態であり、LBから外すと可用性がかえって下がる）。
 */
private fun apap.api.ApapHealthResult.toStatus(): HttpStatusCode =
    when (state) {
        ApapHealthState.UP, ApapHealthState.DEGRADED -> HttpStatusCode.OK
        ApapHealthState.DOWN -> HttpStatusCode.ServiceUnavailable
    }

/**
 * OpenMetrics（Prometheus text exposition format 相当）のContent-Type。
 * `version=1.0.0`を明示するのがOpenMetricsの規約。
 */
private val OPENMETRICS_CONTENT_TYPE =
    ContentType("application", "openmetrics-text").withParameter("version", "1.0.0").withParameter("charset", "utf-8")
