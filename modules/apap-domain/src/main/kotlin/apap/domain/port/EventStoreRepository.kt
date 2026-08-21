package apap.domain.port

import apap.domain.event.DomainEvent
import apap.domain.model.AggregateSnapshot
import kotlin.reflect.KClass

/**
 * 03_基本設計.md 3.4 / 12_ER図.md EVENT_STORE / NFR-DAT-003。
 * 4.5により、Event Sourcing対象（Provider/Model/Alias/Policy/BatchJob）はこのRepository経由で
 * イベント列を永続化し、各Aggregate用Repositoryが読み出したイベントから状態を再構築する。
 *
 * このPortは2つの異なる関心事を提供する（ADR-0014で「両方必要」と決定）。
 * - `append`/`read`: `(stream_id, version)`の楽観ロックで追記整合を担保するイベント列そのもの。
 * - `latestVersion`: 楽観ロック判定用の軽量なバージョン照会（イベント本体を読まない）。
 * - `saveSnapshot`/`loadSnapshot`: NFR-DAT-003が要求するスナップショット機構そのもの。
 *   各Aggregate用Repositoryは、`loadSnapshot`で得たスナップショット以降のイベントのみを
 *   `read(streamId, fromVersion = snapshot.version + 1)`で読み再生することで、
 *   長寿命Aggregate（Provider/Model/Policy等）でも再構築時間を「スナップショット以降の
 *   イベント数」に制限する（全イベント再生を強制しない）。
 *
 * スナップショットの状態そのものは、各Aggregate型（[T]、例: apap.domain.model.provider.Provider）を
 * そのまま保持する。Domain層はシリアライズ方式を規定しない（実際の永続化・(de)serializeは
 * Infrastructure層の実装が担う）。
 */
interface EventStoreRepository {
    fun append(
        streamId: String,
        events: List<DomainEvent>,
        expectedVersion: Long,
    )

    fun read(
        streamId: String,
        fromVersion: Long,
    ): List<DomainEvent>

    /** 楽観ロック用の軽量なバージョン照会（スナップショットとは別の関心事）。 */
    fun latestVersion(streamId: String): Long

    fun <T : Any> saveSnapshot(snapshot: AggregateSnapshot<T>)

    fun <T : Any> loadSnapshot(
        streamId: String,
        stateType: KClass<T>,
    ): AggregateSnapshot<T>?
}
