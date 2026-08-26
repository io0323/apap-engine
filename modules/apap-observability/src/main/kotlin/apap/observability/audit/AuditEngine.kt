package apap.observability.audit

import apap.domain.event.DomainEvent
import apap.domain.event.RequestCompleted
import apap.domain.event.RequestFailed
import apap.domain.event.RequestReceived
import apap.domain.event.RequestStarted
import apap.domain.model.audit.AuditRecord
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.Cost
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.domain.port.AuditRepository
import apap.domain.port.DomainEventSubscriber
import apap.domain.port.IdGenerator
import apap.infrastructure.eventbus.IdempotentEventHandler
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 02_システム仕様.md 2.18 Audit Engine。[eventSubscriber]（Event Bus）を購読し、
 * [RequestReceived]〜[RequestStarted]〜[RequestCompleted]/[RequestFailed]の
 * イベント列を`requestId`で相関させて[AuditRecord]を組み立て、[auditRepository]
 * （追記専用ストア）へ保存する。
 *
 * - **同期実行パスをブロックしない**: 購読ハンドラ自体は`eventId`冪等チェック＋
 *   単一スレッドExecutorへのsubmitのみを行い、即座に戻る。実際の相関・永続化は
 *   専用ワーカースレッド上で非同期に行う（`publish()`呼び出し元はストア書込を待たない）。
 * - **冪等性**: [IdempotentEventHandler]で`eventId`基準の重複配送を排除する
 *   （at-least-once配送前提、apap-infrastructureのEvent Bus実装と同じ規約）。
 * - **本文保存**: 既定ではハッシュ（SHA-256）のみを保持し、`AuditRecord.requestBody`は
 *   `null`のまま保存しない。[AuditConfig.bodyStorageOptIn]がtrueの場合のみ、
 *   [AuditConfig.maskingStrategy]でマスキングした本文を保存する（マスキング未設定での
 *   opt-in有効化は[AuditConfig]生成側・本クラスのinitでガードする）。
 * - **相関欠落時**: 同一プロセス内であれば`RequestReceived`は`RequestCompleted`/
 *   `RequestFailed`より必ず先に処理される（Event Busは同期publish、Executorは単一スレッドで
 *   投入順に処理するため順序が保たれる）。相関エントリが見つからない場合は異常系とみなし、
 *   不完全なAuditRecordを推測で埋めるのではなくログのみに留めて記録をスキップする。
 */
class AuditEngine(
    eventSubscriber: DomainEventSubscriber,
    private val auditRepository: AuditRepository,
    private val config: AuditConfig,
    private val idGenerator: IdGenerator,
) : AutoCloseable {
    init {
        require(!config.bodyStorageOptIn || config.maskingStrategy != null) {
            "AuditConfig.bodyStorageOptIn requires a MaskingStrategy to be configured"
        }
        eventSubscriber.subscribe(IdempotentEventHandler(::enqueue))
    }

    private val executor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "audit-engine-writer").apply { isDaemon = true } }
    private val correlations = ConcurrentHashMap<String, Correlation>()

    private fun enqueue(event: DomainEvent) {
        executor.submit { process(event) }
    }

    private fun process(event: DomainEvent) {
        when (event) {
            is RequestReceived -> correlations[event.requestId.value] = Correlation(event)
            is RequestStarted -> correlations[event.requestId.value]?.routingDecision = event.routingDecision
            is RequestCompleted -> recordCompleted(event)
            is RequestFailed -> recordFailed(event)
            else -> Unit
        }
    }

    private fun recordCompleted(event: RequestCompleted) {
        val correlation =
            correlations.remove(event.requestId.value) ?: return warnMissingCorrelation(event.requestId.value)
        auditRepository.append(
            AuditRecord(
                auditId = idGenerator.newId(),
                requestId = event.requestId,
                traceId = event.meta.traceId,
                tenantId = correlation.tenantId,
                principal = correlation.principal,
                capabilityId = correlation.capabilityId,
                modelAlias = correlation.modelAlias,
                providerId = ProviderId(event.provider),
                modelId = ModelId(event.model),
                routingDecision = correlation.routingDecision,
                requestDigest = digest(event.requestBody),
                responseDigest = event.responseBody?.let { digest(it) },
                requestBody = storableBody(event.requestBody),
                status = event.finishReason.name,
                errorCode = null,
                usage = event.usage,
                cost = event.cost,
                durationMs = event.durationMs,
                retries = event.retries,
                fallbacks = event.fallbacks,
                conversationId = correlation.conversationId,
                occurredAt = event.meta.occurredAt,
            ),
        )
    }

    private fun recordFailed(event: RequestFailed) {
        val correlation =
            correlations.remove(event.requestId.value) ?: return warnMissingCorrelation(event.requestId.value)
        auditRepository.append(
            AuditRecord(
                auditId = idGenerator.newId(),
                requestId = event.requestId,
                traceId = event.meta.traceId,
                tenantId = correlation.tenantId,
                principal = correlation.principal,
                capabilityId = correlation.capabilityId,
                modelAlias = correlation.modelAlias,
                providerId = null,
                modelId = null,
                routingDecision = correlation.routingDecision,
                requestDigest = digest(event.requestBody),
                responseDigest = null,
                requestBody = storableBody(event.requestBody),
                status = "FAILED",
                errorCode = event.errorCode,
                usage = ZERO_USAGE,
                cost = ZERO_COST,
                durationMs = event.durationMs,
                retries = event.attempts,
                fallbacks = event.fallbacks,
                conversationId = correlation.conversationId,
                occurredAt = event.meta.occurredAt,
            ),
        )
    }

    private fun storableBody(raw: String): String? {
        if (!config.bodyStorageOptIn) return null
        return config.maskingStrategy?.mask(raw)
    }

    private fun warnMissingCorrelation(requestId: String) {
        logger.warn(
            "audit record skipped for requestId={}: no RequestReceived correlation found " +
                "(should not happen within a single process)",
            requestId,
        )
    }

    /** テスト・グレースフルシャットダウン向け: これまでenqueueされた処理が完了するまで待機する。 */
    fun awaitQuiescence(timeout: Duration = Duration.ofSeconds(QUIESCENCE_TIMEOUT_SECONDS)) {
        executor.submit {}.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun close() {
        executor.shutdown()
    }

    private class Correlation(
        event: RequestReceived,
    ) {
        val tenantId: TenantId = event.tenantId
        val principal: String = event.principal
        val capabilityId: String = event.capabilityId.value
        val modelAlias: String? = event.modelAlias
        val conversationId: ConversationId? = event.conversationId
        var routingDecision: String = ""
    }

    private companion object {
        val logger = LoggerFactory.getLogger(AuditEngine::class.java)
        const val QUIESCENCE_TIMEOUT_SECONDS = 5L
        val ZERO_USAGE = Usage.of(TokenCount.ZERO, TokenCount.ZERO)
        val ZERO_COST = Cost(Money(BigDecimal.ZERO, "USD"))

        fun digest(text: String): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return hash.joinToString(separator = "") { "%02x".format(it) }
        }
    }
}
