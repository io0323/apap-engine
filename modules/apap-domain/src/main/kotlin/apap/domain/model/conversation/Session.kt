package apap.domain.model.conversation

import apap.domain.model.IllegalStateTransitionException
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import java.time.Instant

enum class SessionStatus { ACTIVE, EXPIRED, REVOKED }

class SessionNotUsableException(
    sessionId: SessionId,
    status: SessionStatus,
) : IllegalStateTransitionException("Session $sessionId is not usable (status=$status)")

/** 04_ドメイン設計.md 4.3.5 Session Aggregate（Root）: 失効後の利用不可。 */
data class Session(
    val sessionId: SessionId,
    val tenantId: TenantId,
    val principal: String,
    val attributes: Map<String, String> = emptyMap(),
    val expiresAt: Instant,
    val status: SessionStatus = SessionStatus.ACTIVE,
) {
    init {
        require(principal.isNotBlank()) { "principal must not be blank" }
    }

    /** 失効後（EXPIRED/REVOKED、または期限切れ）は利用不可。 */
    fun requireUsable(now: Instant) {
        if (status != SessionStatus.ACTIVE || !now.isBefore(expiresAt)) {
            throw SessionNotUsableException(sessionId, status)
        }
    }

    fun expire(): Session = copy(status = SessionStatus.EXPIRED)

    fun revoke(): Session = copy(status = SessionStatus.REVOKED)
}
