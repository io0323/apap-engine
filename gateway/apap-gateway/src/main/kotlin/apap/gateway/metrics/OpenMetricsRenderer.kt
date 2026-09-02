package apap.gateway.metrics

import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.data.MetricDataType
import io.opentelemetry.sdk.metrics.export.MetricReader
import io.opentelemetry.sdk.metrics.export.CollectionRegistration
import java.util.concurrent.atomic.AtomicReference

/**
 * `/metrics`（本タスク指示7）をOpenMetricsテキスト形式で描画する。
 *
 * `apap-runtime`はOpenTelemetry **API**のみに依存し、SDKは宿主が注入する契約
 * （CLAUDE.md不変条件6）なので、SDKを持つのは宿主であるGatewayの責務。
 *
 * 公式のPrometheus ExporterはHTTPサーバを自前で立てる形（別ポート）であり、
 * 「Ktorの`/metrics`で返す」という要件に素直に合わない。ここで必要なのは
 * 収集済みメトリクスのテキスト化だけなので、[InMemoryCollectingReader]で集めた
 * [MetricData]を最小限のOpenMetrics形式へ整形する
 * （要件充足に影響しない実装判断のためADR化せず根拠をここに記す）。
 */
class OpenMetricsRenderer(
    private val reader: InMemoryCollectingReader,
) {
    fun render(): String {
        val metrics = reader.collectAll()
        if (metrics.isEmpty()) {
            // 「メトリクスが1件も無い」ことと「エンドポイントが壊れている」ことを
            // 区別できるよう、空でもEOFだけは返す（OpenMetricsは`# EOF`終端が必須）。
            return EOF_LINE
        }
        return buildString {
            metrics.sortedBy { it.name }.forEach { metric -> appendMetric(metric) }
            append(EOF_LINE)
        }
    }

    private fun StringBuilder.appendMetric(metric: MetricData) {
        val name = metric.name.replace('.', '_').replace('-', '_')
        val type = metric.openMetricsType()
        append("# TYPE ").append(name).append(' ').append(type).append('\n')
        if (metric.description.isNotBlank()) {
            append("# HELP ").append(name).append(' ').append(metric.description.singleLine()).append('\n')
        }
        when (metric.type) {
            MetricDataType.LONG_SUM ->
                metric.longSumData.points.forEach { point ->
                    appendSample(name, point.attributes.asLabels(), point.value.toString())
                }
            MetricDataType.DOUBLE_SUM ->
                metric.doubleSumData.points.forEach { point ->
                    appendSample(name, point.attributes.asLabels(), point.value.toString())
                }
            MetricDataType.LONG_GAUGE ->
                metric.longGaugeData.points.forEach { point ->
                    appendSample(name, point.attributes.asLabels(), point.value.toString())
                }
            MetricDataType.DOUBLE_GAUGE ->
                metric.doubleGaugeData.points.forEach { point ->
                    appendSample(name, point.attributes.asLabels(), point.value.toString())
                }
            MetricDataType.HISTOGRAM ->
                metric.histogramData.points.forEach { point ->
                    val labels = point.attributes.asLabels()
                    appendSample("${name}_count", labels, point.count.toString())
                    appendSample("${name}_sum", labels, point.sum.toString())
                }
            // 明示的に未対応と分かる形にする（黙って落とすと「メトリクスが無い」と誤読される）。
            else -> append("# UNSUPPORTED ").append(name).append(' ').append(metric.type.name).append('\n')
        }
    }

    private fun StringBuilder.appendSample(
        name: String,
        labels: String,
        value: String,
    ) {
        append(name)
        if (labels.isNotEmpty()) append('{').append(labels).append('}')
        append(' ').append(value).append('\n')
    }

    private fun MetricData.openMetricsType(): String =
        when (type) {
            MetricDataType.LONG_SUM, MetricDataType.DOUBLE_SUM -> "counter"
            MetricDataType.LONG_GAUGE, MetricDataType.DOUBLE_GAUGE -> "gauge"
            MetricDataType.HISTOGRAM, MetricDataType.EXPONENTIAL_HISTOGRAM -> "histogram"
            else -> "untyped"
        }

    private fun io.opentelemetry.api.common.Attributes.asLabels(): String =
        asMap().entries.joinToString(",") { (key, value) ->
            "${key.key.replace('.', '_')}=\"${value.toString().escapeLabelValue()}\""
        }

    private fun String.escapeLabelValue(): String = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun String.singleLine(): String = replace('\n', ' ')

    private companion object {
        const val EOF_LINE = "# EOF\n"
    }
}

/**
 * `/metrics`が叩かれたタイミングで最新値を取り出せるようにするための[MetricReader]。
 * OpenTelemetry SDKの`InMemoryMetricReader`相当を、SDK本体（opentelemetry-sdk）だけで
 * 賄うために自前で持つ（`opentelemetry-sdk-testing`はテスト専用アーティファクトなので
 * 本番依存には加えない）。
 */
class InMemoryCollectingReader : MetricReader {
    private val registration = AtomicReference<CollectionRegistration>(CollectionRegistration.noop())

    override fun register(registration: CollectionRegistration) {
        this.registration.set(registration)
    }

    fun collectAll(): List<MetricData> = registration.get().collectAllMetrics().toList()

    override fun getAggregationTemporality(
        instrumentType: io.opentelemetry.sdk.metrics.InstrumentType,
    ): AggregationTemporality = AggregationTemporality.CUMULATIVE

    override fun forceFlush(): io.opentelemetry.sdk.common.CompletableResultCode =
        io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess()

    override fun shutdown(): io.opentelemetry.sdk.common.CompletableResultCode =
        io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess()
}
