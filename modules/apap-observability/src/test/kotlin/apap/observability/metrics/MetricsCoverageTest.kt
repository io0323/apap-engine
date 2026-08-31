package apap.observability.metrics

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 02_システム仕様.md 2.19 Monitoring仕様のメトリクス名を、[apap.domain.event.DomainEventCoverageTest]と
 * 同様にクローズドセットとして扱う。2.19の表は11行だが「apap_retries_total / apap_fallbacks_total」の
 * 1行は2つの独立したメトリクス名を表すため、期待集合は12件になる。
 *
 * メトリクス名はDomain Eventのクラス名と異なりKotlinの宣言（クラス/関数）として現れず、
 * [OpenTelemetryMetricsRecorder]内でOpenTelemetryのbuilderへ渡す文字列リテラルとしてのみ存在する。
 * そのためKonsistの宣言解析ではなくテキストスキャンで、このファイル中の`"apap_..."`リテラルの集合が
 * 2.19の期待集合と過不足なく一致することを検証する。
 */
class MetricsCoverageTest {
    private val expectedMetricNames =
        setOf(
            "apap_requests_total",
            "apap_request_duration_seconds",
            "apap_overhead_duration_seconds",
            "apap_tokens_total",
            "apap_cost_total",
            "apap_cache_events_total",
            "apap_retries_total",
            "apap_fallbacks_total",
            "apap_circuit_breaker_state",
            "apap_provider_health",
            "apap_streaming_connections",
            "apap_rate_limit_events_total",
        )

    @Test
    fun `2 19 chapter lists exactly 12 metric names`() {
        assertTrue(expectedMetricNames.size == METRIC_COUNT) {
            "expected $METRIC_COUNT metric names, found ${expectedMetricNames.size}"
        }
    }

    @Test
    fun `every metric name from chapter 2 19 is registered by OpenTelemetryMetricsRecorder`() {
        val declaredNames = metricNameLiteralsInRecorderSource()
        val missing = expectedMetricNames - declaredNames
        assertTrue(missing.isEmpty()) { "Missing metric registrations for: $missing" }
    }

    @Test
    fun `OpenTelemetryMetricsRecorder does not register any metric name outside chapter 2 19`() {
        val declaredNames = metricNameLiteralsInRecorderSource()
        val extra = declaredNames - expectedMetricNames
        assertTrue(extra.isEmpty()) { "Unexpected metric registrations not in 2.19: $extra" }
    }

    private fun metricNameLiteralsInRecorderSource(): Set<String> {
        val repoRoot = findRepoRoot(File(".").canonicalFile)
        val recorderFile =
            File(
                repoRoot,
                "modules/apap-observability/src/main/kotlin/apap/observability/metrics/" +
                    "OpenTelemetryMetricsRecorder.kt",
            )
        check(recorderFile.exists()) { "OpenTelemetryMetricsRecorder.ktが見つかりません: ${recorderFile.path}" }
        val text = recorderFile.readText()
        val literalPattern = Regex(""""(apap_[a-z_]+)"""")
        return literalPattern.findAll(text).map { it.groupValues[1] }.toSet()
    }

    private fun findRepoRoot(start: File): File {
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts が見つからず、リポジトリルートを特定できません（起点: $start）")
    }

    private companion object {
        const val METRIC_COUNT = 12
    }
}
