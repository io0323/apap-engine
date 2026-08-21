package apap.domain.event

import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId

/** 14_イベント一覧.md 14.4 セキュリティ・構成系。 */
data class CredentialRotated(
    override val meta: EventMetadata,
    val providerId: ProviderId,
    val oldVersion: Int,
    val newVersion: Int,
) : DomainEvent

data class CredentialValidationFailed(
    override val meta: EventMetadata,
    val providerId: ProviderId,
    val reason: String,
) : DomainEvent

data class PolicyUpdated(
    override val meta: EventMetadata,
    val policyId: String,
    val scope: String,
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
