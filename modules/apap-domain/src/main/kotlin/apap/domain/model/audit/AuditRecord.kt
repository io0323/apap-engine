package apap.domain.model.audit

import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.Cost
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.Usage
import java.time.Instant

/**
 * 12_ER図.md AUDIT_RECORD / 02_システム仕様.md 2.18: 追記専用の監査記録。
 * `AuditRepository`はappend/searchのみを提供し、更新・削除は仕様上禁止される
 * （4.1 Audit Context: Append-only）。
 */
data class AuditRecord(
    val auditId: String,
    val requestId: RequestId,
    val traceId: String,
    val tenantId: TenantId,
    val principal: String,
    val capabilityId: String,
    val modelAlias: String? = null,
    val providerId: ProviderId? = null,
    val modelId: ModelId? = null,
    val routingDecision: String,
    val requestDigest: String,
    val responseDigest: String? = null,
    val requestBody: String? = null,
    val status: String,
    val errorCode: ErrorCode? = null,
    val usage: Usage,
    val cost: Cost,
    val durationMs: Long,
    val retries: Int,
    val fallbacks: Int,
    val conversationId: ConversationId? = null,
    val occurredAt: Instant,
) {
    init {
        require(principal.isNotBlank()) { "principal must not be blank" }
        require(status.isNotBlank()) { "status must not be blank" }
        require(durationMs >= 0) { "durationMs must not be negative: $durationMs" }
        require(retries >= 0) { "retries must not be negative: $retries" }
        require(fallbacks >= 0) { "fallbacks must not be negative: $fallbacks" }
    }
}

/** 02_システム仕様.md 2.18: 期間・テナント・Provider・エラー種別・request_idで検索。 */
data class AuditSearchCriteria(
    val fromInclusive: Instant? = null,
    val toExclusive: Instant? = null,
    val tenantId: TenantId? = null,
    val providerId: ProviderId? = null,
    val errorCode: ErrorCode? = null,
    val requestId: RequestId? = null,
)
