package apap.domain.event

import apap.domain.model.execution.CbState
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.CbKey
import apap.domain.model.vo.Cost
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.FinishReason
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.Usage

/** 14_イベント一覧.md 14.2 リクエスト実行系。 */
data class RequestReceived(
    override val meta: EventMetadata,
    val requestId: RequestId,
    val capabilityId: CapabilityId,
    val tenantId: TenantId,
) : DomainEvent

data class RequestStarted(
    override val meta: EventMetadata,
    val requestId: RequestId,
    val capabilityId: CapabilityId,
    val tenantId: TenantId,
) : DomainEvent

/**
 * 4.7の代表例は{requestId, provider, model, usage, cost, durationMs}のみを示すが、
 * 02_システム仕様.md 2.9のFinishReason正規化結果はRequestCompletedが表す
 * 「レスポンス正規化の完了」そのものに属する情報のため`finishReason`として保持する
 * （4.7の代表フィールド一覧は網羅列挙ではなく代表例であり、ER図12章に定義のないDomain Eventの
 * フィールド追加は12章と矛盾しない）。
 */
data class RequestCompleted(
    override val meta: EventMetadata,
    val requestId: RequestId,
    val provider: String,
    val model: String,
    val usage: Usage,
    val cost: Cost,
    val durationMs: Long,
    val finishReason: FinishReason,
) : DomainEvent

data class RequestFailed(
    override val meta: EventMetadata,
    val requestId: RequestId,
    val errorCode: ErrorCode,
    val attempts: Int,
    val fallbacks: Int,
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
