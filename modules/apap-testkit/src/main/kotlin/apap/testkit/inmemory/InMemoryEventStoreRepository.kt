package apap.testkit.inmemory

import apap.domain.event.DomainEvent
import apap.domain.model.AggregateSnapshot
import apap.domain.port.EventStoreRepository
import kotlin.reflect.KClass

/** ADR-0014が要求する楽観ロック違反を、実Infrastructure実装同様に例外として表面化させる。 */
class EventStreamConcurrencyException(
    streamId: String,
    expectedVersion: Long,
    actualVersion: Long,
) : IllegalStateException(
        "Concurrent append to stream $streamId: expectedVersion=$expectedVersion, actualVersion=$actualVersion",
    )

/**
 * `apap.domain.port.EventStoreRepository`のIn-Memory実装（[apap.testkit]）。
 * ADR-0014の通り、append/read/latestVersionと、saveSnapshot/loadSnapshotの両方を提供する。
 */
class InMemoryEventStoreRepository : EventStoreRepository {
    private val streams = mutableMapOf<String, MutableList<DomainEvent>>()
    private val snapshots = mutableMapOf<Pair<String, KClass<*>>, AggregateSnapshot<*>>()

    override fun append(
        streamId: String,
        events: List<DomainEvent>,
        expectedVersion: Long,
    ) {
        val stream = streams.getOrPut(streamId) { mutableListOf() }
        val actualVersion = stream.size.toLong()
        if (actualVersion != expectedVersion) {
            throw EventStreamConcurrencyException(streamId, expectedVersion, actualVersion)
        }
        stream.addAll(events)
    }

    override fun read(
        streamId: String,
        fromVersion: Long,
    ): List<DomainEvent> {
        val stream = streams[streamId].orEmpty()
        val skip = if (fromVersion <= 0) 0 else (fromVersion - 1).toInt().coerceAtMost(stream.size)
        return stream.drop(skip)
    }

    override fun latestVersion(streamId: String): Long = streams[streamId]?.size?.toLong() ?: 0L

    override fun <T : Any> saveSnapshot(snapshot: AggregateSnapshot<T>) {
        snapshots[snapshot.streamId to snapshot.state::class] = snapshot
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> loadSnapshot(
        streamId: String,
        stateType: KClass<T>,
    ): AggregateSnapshot<T>? = snapshots[streamId to stateType] as AggregateSnapshot<T>?

    fun clear() {
        streams.clear()
        snapshots.clear()
    }
}
