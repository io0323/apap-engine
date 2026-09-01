package apap.infrastructure.jdbc

import apap.domain.event.DomainEvent
import apap.domain.model.AggregateSnapshot
import apap.domain.port.EventStoreRepository
import kotlin.reflect.KClass
import apap.domain.model.reconstruct as foldEvents

private const val DEFAULT_SNAPSHOT_INTERVAL = 100

/**
 * ADR-0026: `Jdbc*Repository`（Provider/Model/ModelAlias/RoutingPolicy/BatchJob）が共通して行う
 * 「スナップショット＋差分イベント再生による再構築」と「イベント追記＋イベント件数ベースの
 * スナップショット取得（既定100件ごと）」を1箇所にまとめる（5リポジトリでの重複を避ける）。
 *
 * [EventStoreRepository.saveEvents]相当のexpectedVersionは、各Repository Portの`saveEvents`
 * シグネチャ自体が呼び出し側から受け取らないため、ここで[EventStoreRepository.latestVersion]を
 * 読んでから[EventStoreRepository.append]する。読取と追記の間に競合が入り込む余地はあるが、
 * 実際の競合検出は`append`内部の`(stream_id, version)`一意制約違反（[EventStreamConcurrencyException]）
 * に委ねているため、後勝ちのINSERTが確実に失敗し整合性が壊れることはない
 * （[JdbcEventStoreRepository]のKDocが述べる設計判断と同じ理由）。
 */
internal class EventSourcedRepositorySupport<T : Any>(
    private val eventStoreRepository: EventStoreRepository,
    private val stateType: KClass<T>,
    private val apply: (T?, DomainEvent) -> T,
    private val snapshotEveryNEvents: Int = DEFAULT_SNAPSHOT_INTERVAL,
) {
    fun reconstruct(streamId: String): T? {
        val snapshot = eventStoreRepository.loadSnapshot(streamId, stateType)
        val events = eventStoreRepository.read(streamId, fromVersion = (snapshot?.version ?: 0L) + 1)
        return foldEvents(events, snapshot?.state, apply)
    }

    fun saveEvents(
        streamId: String,
        events: List<DomainEvent>,
    ) {
        if (events.isEmpty()) return
        val expectedVersion = eventStoreRepository.latestVersion(streamId)
        eventStoreRepository.append(streamId, events, expectedVersion)
        maybeSnapshot(streamId)
    }

    private fun maybeSnapshot(streamId: String) {
        val snapshot = eventStoreRepository.loadSnapshot(streamId, stateType)
        val latest = eventStoreRepository.latestVersion(streamId)
        val eventsSinceSnapshot = latest - (snapshot?.version ?: 0L)
        if (eventsSinceSnapshot < snapshotEveryNEvents) return
        val state = reconstruct(streamId) ?: return
        eventStoreRepository.saveSnapshot(AggregateSnapshot(streamId, latest, state))
    }
}
