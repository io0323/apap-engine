package apap.infrastructure.eventbus

import apap.domain.event.DomainEvent
import org.slf4j.LoggerFactory

/**
 * 03_基本設計.md 3.11 / 14_イベント一覧.md「外部Event Bus転送」の送出口。
 * 実ネットワーククライアント（メッセージング製品）との結合は本SPIの実装側に閉じ込め、
 * [apap.infrastructure.eventbus.SynchronousEventBus] 自体は特定製品を知らない。
 *
 * at-least-once配送が前提のため、購読側は[SynchronousEventBus.subscribeIdempotent]等で
 * `eventId`基準の冪等処理を行うこと。
 */
fun interface ExternalEventBusForwarder {
    fun forward(event: DomainEvent)
}

/**
 * 既定の外部転送先。単一プロセス埋込利用（ADR-0001の既定方針）では外部Busへの転送が
 * 不要なため、実配送は行わずログのみ記録する。マルチノード運用で実メッセージング製品へ
 * 転送する場合は、埋込側が[ExternalEventBusForwarder]の実装を差し替えて
 * [SynchronousEventBus]へ渡すこと（実クライアント統合はP8の本作業スコープ外、後続タスク）。
 */
class LoggingExternalEventBusForwarder : ExternalEventBusForwarder {
    override fun forward(event: DomainEvent) {
        logger.debug(
            "no external event bus configured; not forwarding eventId={} type={}",
            event.meta.eventId,
            event::class.simpleName,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(LoggingExternalEventBusForwarder::class.java)
    }
}
