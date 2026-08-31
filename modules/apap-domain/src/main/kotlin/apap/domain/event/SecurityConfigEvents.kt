package apap.domain.event

import apap.domain.model.routing.PolicyRule
import apap.domain.model.routing.PolicyStatus
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId

/**
 * 14_イベント一覧.md 14.4 セキュリティ・構成系。
 *
 * ADR-0026: [CredentialRotated]/[PolicyUpdated]は元々「通知」目的の設計で、Aggregateの
 * フル状態復元に必要なフィールドを持たなかった。イベント名は14章から一切変えず、
 * 再構築に必要なフィールドのみを追加してある。
 */
data class CredentialRotated(
    override val meta: EventMetadata,
    val providerId: ProviderId,
    val oldVersion: Int,
    val newVersion: Int,
    val newSecretRef: String,
) : DomainEvent

data class CredentialValidationFailed(
    override val meta: EventMetadata,
    val providerId: ProviderId,
    val reason: String,
) : DomainEvent

/**
 * [scope]は[apap.domain.model.routing.PolicyScope]の文字列表現（14章の既存表記を維持）。
 * [rules]/[version]/[status]は[apap.domain.model.routing.RoutingPolicy]の再構築に必須。
 */
data class PolicyUpdated(
    override val meta: EventMetadata,
    val policyId: String,
    val scope: String,
    val tenantId: TenantId? = null,
    val workflowId: String? = null,
    val rules: List<PolicyRule>,
    val version: Int,
    val status: PolicyStatus,
) : DomainEvent

data class QuotaPolicyUpdated(
    override val meta: EventMetadata,
    val quotaId: String,
) : DomainEvent

data class BudgetUpdated(
    override val meta: EventMetadata,
    val budgetId: String,
) : DomainEvent

data class AccessDenied(
    override val meta: EventMetadata,
    val requestId: RequestId?,
    val reason: String,
) : DomainEvent
