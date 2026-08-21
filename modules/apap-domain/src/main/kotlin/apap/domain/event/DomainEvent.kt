package apap.domain.event

import apap.domain.model.vo.TenantId
import java.time.Instant

/**
 * 04_ドメイン設計.md 4.7 / 14_イベント一覧.md: 全Domain Eventの共通属性。
 * eventId(ULID) / occurredAt / traceId / tenantId? / aggregateId / version。
 * 命名は過去形。イベント名は14章の名称と1文字も違えない（勝手なリネーム禁止）。
 * 購読側は`eventId`で冪等に処理する。
 */
sealed interface DomainEvent {
    val meta: EventMetadata
}

/**
 * [DomainEvent]の共通属性をまとめたもの。個々のイベントは`meta`として1つ保持することで、
 * 50件近いイベント全てに6個の共通プロパティを毎回宣言する重複を避ける。
 */
data class EventMetadata(
    val eventId: String,
    val occurredAt: Instant,
    val traceId: String,
    val tenantId: TenantId?,
    val aggregateId: String,
    val version: Long,
)
