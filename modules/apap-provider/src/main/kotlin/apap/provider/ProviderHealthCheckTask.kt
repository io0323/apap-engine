package apap.provider

import apap.domain.event.EventMetadata
import apap.domain.event.ProviderHealthChanged
import apap.domain.model.provider.Provider
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.vo.ProviderId
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import apap.domain.port.ProviderRepository
import apap.domain.port.ScheduledTask
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * FR-PRV-006 Providerの定期健全性監視（05_シーケンス設計.md 5.10）。
 *
 * ## 解決する問題
 *
 * `HealthCheckService` / `ProviderHealthAggregator` は実装済みだったが、
 * **`ProviderHealthChanged` を発火する主体がどこにも無かった**（P11-F2）。
 * 集約側が正しく配線されていても、イベントが一度も届かないので
 * `/health/providers` は常に初期値を返し、Routingのヘルスフィルタも効かない。
 * 「監視の仕組みがある」ことと「監視が動いている」ことは別である。
 *
 * ## 動作
 *
 * ACTIVE な Provider それぞれについて Adapter の `healthCheck()` を呼び、
 * 前回と状態が変わったときだけ [ProviderHealthChanged] を発火する（毎回発火すると
 * イベントストアと購読側が無意味に膨らむ）。`healthCheck()` が例外を投げた場合は
 * DOWN とみなす——応答しないProviderは健全ではない。
 *
 * ## 冪等性（ADR-0032）
 *
 * 複数ノードで同時に走っても、各ノードが自分の観測結果で状態を上書きするだけで
 * 破壊的な副作用は無い。前回状態はノードごとのメモリに持つため、
 * ノードが増えると同じ遷移が複数回発火しうるが、購読側は `eventId` で冪等に処理する
 * （`IdempotentEventHandler`）。
 */
class ProviderHealthCheckTask(
    private val providerRepository: ProviderRepository,
    private val adapterRegistry: AdapterRegistry,
    private val eventPublisher: DomainEventPublisher,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    override val interval: Duration = DEFAULT_INTERVAL,
) : ScheduledTask {
    override val name: String = "provider-health-check"

    private val lastKnown = ConcurrentHashMap<ProviderId, ProviderHealthStatus>()

    override suspend fun runOnce() {
        providerRepository
            .findAll()
            .filter { it.status == ProviderStatus.ACTIVE }
            .forEach { provider -> checkOne(provider) }
    }

    private suspend fun checkOne(provider: Provider) {
        val observed = probe(provider)
        val previous = lastKnown.put(provider.providerId, observed.status)
        if (previous == observed.status) return
        eventPublisher.publish(
            ProviderHealthChanged(
                meta =
                    EventMetadata(
                        eventId = idGenerator.newId(),
                        occurredAt = clock.now(),
                        traceId = idGenerator.newId(),
                        tenantId = null,
                        aggregateId = provider.providerId.value,
                        version = 0,
                    ),
                providerId = provider.providerId,
                from = previous ?: ProviderHealthStatus.UP,
                to = observed.status,
                evidence = observed.evidence,
            ),
        )
    }

    /** Adapterの応答。到達できない・例外を投げる場合はDOWNとして扱う。 */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun probe(provider: Provider): Observation =
        try {
            val result = adapterRegistry.resolve(provider.adapterPluginId).adapter.healthCheck()
            Observation(result.status, "latencyMs=${result.latency.toMillis()} detail=${result.detail.orEmpty()}")
        } catch (e: Exception) {
            logger.warn("health check failed providerId={}: {}", provider.providerId.value, e.message)
            Observation(ProviderHealthStatus.DOWN, "healthCheck threw ${e::class.simpleName}")
        }

    private data class Observation(
        val status: ProviderHealthStatus,
        val evidence: String,
    )

    companion object {
        /** 05_シーケンス設計.md 5.10「30秒周期」。 */
        val DEFAULT_INTERVAL: Duration = Duration.ofSeconds(30)

        private val logger = LoggerFactory.getLogger(ProviderHealthCheckTask::class.java)
    }
}
