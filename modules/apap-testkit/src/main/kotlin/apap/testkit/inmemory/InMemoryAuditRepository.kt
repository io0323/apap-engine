package apap.testkit.inmemory

import apap.domain.model.audit.AuditRecord
import apap.domain.model.audit.AuditSearchCriteria
import apap.domain.port.AuditRepository

/** 追記専用（update/deleteを提供しない）。 */
class InMemoryAuditRepository : AuditRepository {
    private val records = mutableListOf<AuditRecord>()

    override fun append(record: AuditRecord) {
        records.add(record)
    }

    override fun search(criteria: AuditSearchCriteria): List<AuditRecord> =
        records.filter { record ->
            (criteria.fromInclusive == null || !record.occurredAt.isBefore(criteria.fromInclusive)) &&
                (criteria.toExclusive == null || record.occurredAt.isBefore(criteria.toExclusive)) &&
                (criteria.tenantId == null || record.tenantId == criteria.tenantId) &&
                (criteria.providerId == null || record.providerId == criteria.providerId) &&
                (criteria.errorCode == null || record.errorCode == criteria.errorCode) &&
                (criteria.requestId == null || record.requestId == criteria.requestId)
        }
}
