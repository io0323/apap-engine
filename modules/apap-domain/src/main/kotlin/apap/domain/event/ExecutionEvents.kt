package apap.domain.event

import apap.domain.model.execution.CbState
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.CbKey
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.Cost
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.FinishReason
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.Usage

/**
 * 14_イベント一覧.md 14.2 リクエスト実行系。
 *
 * `principal`/`modelAlias`/`conversationId`は4.7の代表例にはないが、[apap.domain.model.audit.AuditRecord]を
 * `RequestReceived`〜`RequestCompleted`/`RequestFailed`のイベント列だけから（他の同期呼出経路を介さず）
 * 構築するために必要な「リクエスト受付時点の事実」として追加した（ER図12章に定義のないDomain Eventの
 * フィールド追加は12章と矛盾しない、[RequestCompleted]既存コメント参照）。Audit Engineはこれらを
 * `requestId`でこの後のイベント列と相関させ、後続イベントで毎回繰り返さない。
 */
data class RequestReceived(
    override val meta: EventMetadata,
    val requestId: RequestId,
    val capabilityId: CapabilityId,
    val tenantId: TenantId,
    val principal: String,
    val modelAlias: String? = null,
    val conversationId: ConversationId? = null,
) : DomainEvent

/**
 * `routingDecision`は[apap.routing.RoutingEngine]相当が算出した`RoutingDecision.toAuditSummary()`の
 * 結果文字列（policy/候補チェーン/選定理由の要約）。Audit Engineが`requestId`相関でAuditRecordへ
 * 埋め込む（[RequestReceived]のKDoc参照）。
 */
data class RequestStarted(
    override val meta: EventMetadata,
    val requestId: RequestId,
    val capabilityId: CapabilityId,
    val tenantId: TenantId,
    val routingDecision: String,
) : DomainEvent

/**
 * 4.7の代表例は{requestId, provider, model, usage, cost, durationMs}のみを示すが、
 * 02_システム仕様.md 2.9のFinishReason正規化結果はRequestCompletedが表す
 * 「レスポンス正規化の完了」そのものに属する情報のため`finishReason`として保持する
 * （4.7の代表フィールド一覧は網羅列挙ではなく代表例であり、ER図12章に定義のないDomain Eventの
 * フィールド追加は12章と矛盾しない）。
 *
 * `retries`/`fallbacks`/`requestBody`/`responseBody`は同じ理由でAudit Engine向けに追加した
 * （[RequestReceived]のKDoc参照）。`requestBody`/`responseBody`は正規化前の生コンテンツ
 * （`ContentPart`列の文字列表現）を保持するため、Audit Engine以外の購読者・
 * [apap.infrastructure.eventbus.ExternalEventBusForwarder]の実装は読み取り・外部転送しないこと
 * （AUDIT_RECORD.request_bodyは監査ポリシーopt-in時のみ、かつマスキング後にのみ永続化される。
 * 既定はAudit Engineがハッシュ化した上でこれらのフィールド自体は永続化しない）。
 *
 * `capabilityId`は02_システム仕様.md 2.19 `apap_requests_total{tenant, capability, provider, model,
 * status}`用に追加した。Audit Engineは`RequestReceived`相関から取得できるため必須ではなかったが、
 * Metrics Engineは`requestId`相関キャッシュを持たずイベント単体で完結させるため、ここに直接持たせる。
 */
data class RequestCompleted(
    override val meta: EventMetadata,
    val requestId: RequestId,
    val capabilityId: CapabilityId,
    val provider: String,
    val model: String,
    val usage: Usage,
    val cost: Cost,
    val durationMs: Long,
    val finishReason: FinishReason,
    val retries: Int = 0,
    val fallbacks: Int = 0,
    val requestBody: String = "",
    val responseBody: String? = null,
) : DomainEvent

/**
 * `durationMs`/`requestBody`はAudit Engine向けに追加した（[RequestReceived]/[RequestCompleted]の
 * KDoc参照）。`capabilityId`は[RequestCompleted]と同じ理由でMetrics Engine向けに追加した。
 */
data class RequestFailed(
    override val meta: EventMetadata,
    val requestId: RequestId,
    val capabilityId: CapabilityId,
    val errorCode: ErrorCode,
    val attempts: Int,
    val fallbacks: Int,
    val durationMs: Long = 0,
    val requestBody: String = "",
) : DomainEvent

data class RequestCancelled(
    override val meta: EventMetadata,
    val requestId: RequestId,
    val partialUsage: Usage? = null,
) : DomainEvent

data class RetryExecuted(
    override val meta: EventMetadata,
    val requestId: RequestId,
    val candidate: String,
    val attempt: Int,
    val reason: String,
) : DomainEvent

data class FallbackExecuted(
    override val meta: EventMetadata,
    val requestId: RequestId,
    val fromCandidate: String,
    val toCandidate: String,
    val reason: String,
) : DomainEvent

data class CircuitBreakerStateChanged(
    override val meta: EventMetadata,
    val cbKey: CbKey,
    val from: CbState,
    val to: CbState,
) : DomainEvent

enum class CacheType { REQUEST, RESPONSE }

data class CacheHit(
    override val meta: EventMetadata,
    val requestId: RequestId,
    val cacheType: CacheType,
) : DomainEvent

data class CacheStored(
    override val meta: EventMetadata,
    val requestId: RequestId,
    val cacheType: CacheType,
) : DomainEvent

data class StreamOpened(
    override val meta: EventMetadata,
    val requestId: RequestId,
) : DomainEvent

data class StreamClosed(
    override val meta: EventMetadata,
    val requestId: RequestId,
) : DomainEvent

data class StreamAborted(
    override val meta: EventMetadata,
    val requestId: RequestId,
    val reason: String,
) : DomainEvent
