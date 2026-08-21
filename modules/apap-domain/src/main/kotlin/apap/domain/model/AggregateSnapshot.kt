package apap.domain.model

/**
 * NFR-DAT-003「イベントストアは追記専用、スナップショットにより再構築時間を制御」/
 * 03_基本設計.md 3.4 EventStoreRepository.snapshot。
 *
 * Event Sourcing対象Aggregate（Provider/Model/Alias/Policy/BatchJob。4.5参照）の、ある時点
 * （`version`）における完全な状態のスナップショット。Repository実装はこれを
 * [apap.domain.port.EventStoreRepository.loadSnapshot] で取得し、
 * `read(streamId, fromVersion = version + 1)` で以降のイベントのみを再生することで、
 * 長寿命Aggregateであっても再構築コストをスナップショット以降のイベント数に制限する。
 */
data class AggregateSnapshot<T : Any>(
    val streamId: String,
    val version: Long,
    val state: T,
)
