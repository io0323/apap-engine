package apap.runtime

import apap.domain.event.CacheHit
import apap.domain.event.CacheType
import apap.domain.event.EventMetadata
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import apap.infrastructure.eventbus.SynchronousEventBus
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * `ApapEngineBuilder.build()`がEvent Busへ[apap.observability.metrics.MetricsEngine]を
 * **1つだけ**購読させることを検証する。
 *
 * ## なぜこのテストが要るのか
 *
 * P11の総合検証で、`MetricsEngine`が
 *
 * 1. `ExecutionEngineComposer.build()` の中と、
 * 2. `ApapEngineBuilder.build()` の中
 *
 * の**両方**で構築されていることを発見した。`MetricsEngine`はコンストラクタ（`init`）で
 * Event Busを購読し、重複排除に使う`IdempotentEventHandler`のeventId集合は
 * **インスタンスごとに閉じている**（そのKDocに明記）。したがって2インスタンスが購読すると
 * 同一イベントがそれぞれ1回ずつ処理され、**イベント起因のメトリクスがすべて2倍**になる。
 *
 * 既存の`CapabilitySmokeTest`はこれを検出できない。あちらは`ExecutionEngineComposer`を
 * 直接使うハーネスで`ApapEngineBuilder`を通らないため、`MetricsEngine`が1つしか作られない。
 * 「本番の入口を通っていないテストは本番の配線を検証できない」という、本プロジェクトが
 * 繰り返している失敗の形そのものなので、ここは必ずビルダ経由で検証する。
 *
 * 検証方法は実測値。`CacheHit`はリポジトリ参照を伴わない単純なカウンタ加算
 * （`MetricsEngine.dispatch`参照）なので、イベントを1件流してカウンタが1であることを見る。
 * 購読数のような内部構造には依存しない。
 */
class MetricsSubscriptionTest {
    @Test
    fun `an event published once increments the metric exactly once`() {
        val reader = InMemoryMetricReader.create()
        val meterProvider = SdkMeterProvider.builder().registerMetricReader(reader).build()
        val bus = SynchronousEventBus().let { ApapEngineBuilder.DomainEventBus(it, it) }

        val engine =
            ApapEngineBuilder()
                .eventBus(bus)
                .meter(meterProvider.get("apap-runtime-test"))
                .build()

        engine.use {
            bus.publisher.publish(cacheHit())
        }

        val cacheEvents =
            reader
                .collectAllMetrics()
                .firstOrNull { it.name == CACHE_EVENTS_METRIC }

        // 収集経路が生きていることの確認を先に置く。メトリクスが1件も取れていない状態で
        // 「2倍になっていない」と判定すると、シグナルの不在を成功と読むことになる。
        assertTrue(
            cacheEvents != null,
            "$CACHE_EVENTS_METRIC が収集できていません。MetricsEngineが購読していないか、" +
                "収集経路が壊れています。この状態では二重購読を検出できません。",
        )

        val total = cacheEvents!!.longSumData.points.sumOf { it.value }
        assertEquals(
            1L,
            total,
            "CacheHitを1件だけ publish したのに $CACHE_EVENTS_METRIC が $total です。" +
                "2ならMetricsEngineがEvent Busへ二重に購読しています" +
                "（ApapEngineBuilderとExecutionEngineComposerの両方で構築していないか確認すること）。",
        )
    }

    private fun cacheHit() =
        CacheHit(
            meta =
                EventMetadata(
                    eventId = "01ARZ3NDEKTSV4RRFFQ69G5FB1",
                    occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    traceId = "trace-1",
                    tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FB2"),
                    aggregateId = "01ARZ3NDEKTSV4RRFFQ69G5FB3",
                    version = 1L,
                ),
            requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FB4"),
            cacheType = CacheType.REQUEST,
        )

    private companion object {
        const val CACHE_EVENTS_METRIC = "apap_cache_events_total"
    }
}
