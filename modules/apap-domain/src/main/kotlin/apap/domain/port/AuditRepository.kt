package apap.domain.port

import apap.domain.model.audit.AuditRecord
import apap.domain.model.audit.AuditSearchCriteria

/** 追記専用（append-only）。update/deleteは提供しない。 */
interface AuditRepository {
    fun append(record: AuditRecord)

    fun search(criteria: AuditSearchCriteria): List<AuditRecord>
}
