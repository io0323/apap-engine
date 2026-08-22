package apap.context

import apap.domain.event.DomainEvent
import apap.domain.event.EventMetadata
import apap.domain.event.SessionCreated
import apap.domain.event.SessionExpired
import apap.domain.event.SessionRevoked
import apap.domain.model.conversation.Session
import apap.domain.model.conversation.SessionStatus
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import apap.domain.port.SessionRepository
import java.time.Duration
import java.time.Instant

class SessionNotFoundException(
    sessionId: SessionId,
) : NoSuchElementException("Session not found: $sessionId")

/** CLAUDE.md不変条件7に従いすべて設定可能。02_システム仕様.md 2.15: 既定24h、スライディング更新可。 */
data class SessionManagerConfig(
    val defaultTtl: Duration = Duration.ofHours(DEFAULT_TTL_HOURS),
    val slidingRefresh: Boolean = true,
) {
    init {
        require(!defaultTtl.isNegative && !defaultTtl.isZero) { "defaultTtl must be positive: $defaultTtl" }
    }

    private companion object {
        const val DEFAULT_TTL_HOURS = 24L
    }
}

/**
 * 02_システム仕様.md 2.15 / 04_ドメイン設計.md 4.3.5 Session Aggregate: 発行/検証/失効の
 * オーケストレーション。状態遷移そのものは[Session]自身（`expire()`/`revoke()`/`requireUsable()`）が
 * 強制する。
 */
class SessionManager(
    private val repository: SessionRepository,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val eventPublisher: DomainEventPublisher,
    private val config: SessionManagerConfig = SessionManagerConfig(),
) {
    fun issue(
        tenantId: TenantId,
        principal: String,
        traceId: String,
        attributes: Map<String, String> = emptyMap(),
    ): Session {
        val now = clock.now()
        val session =
            Session(
                sessionId = SessionId(idGenerator.newId()),
                tenantId = tenantId,
                principal = principal,
                attributes = attributes,
                expiresAt = now.plus(config.defaultTtl),
            )
        repository.save(session)
        publish(SessionCreated(meta(session.sessionId, tenantId, traceId), session.sessionId, principal))
        return session
    }

    /**
     * 有効なSessionを返す。期限切れの場合はその場でEXPIREDへ遷移・永続化して[SessionExpired]を
     * 発火したうえで、[apap.domain.model.conversation.SessionNotUsableException]を送出する
     * （4.3.5「失効後の利用不可」）。スライディング更新が有効なら検証成功時に[expiresAt]を延長する。
     */
    fun verify(
        sessionId: SessionId,
        traceId: String,
    ): Session {
        val session = findOrThrow(sessionId)
        val now = clock.now()
        val lazilyExpired = lazilyExpireIfNeeded(session, now, traceId)
        lazilyExpired.requireUsable(now)
        if (!config.slidingRefresh) {
            return lazilyExpired
        }
        val refreshed = lazilyExpired.copy(expiresAt = now.plus(config.defaultTtl))
        repository.save(refreshed)
        return refreshed
    }

    /** [SessionRepository]に`revoke`が無いため、`.revoke()`した結果を明示的に`save()`する。 */
    fun revoke(
        sessionId: SessionId,
        reason: String,
        traceId: String,
    ) {
        val session = findOrThrow(sessionId)
        repository.save(session.revoke())
        publish(SessionRevoked(meta(sessionId, session.tenantId, traceId), sessionId, reason))
    }

    fun expire(
        sessionId: SessionId,
        traceId: String,
    ) {
        val session = findOrThrow(sessionId)
        repository.expire(sessionId)
        publish(SessionExpired(meta(sessionId, session.tenantId, traceId), sessionId))
    }

    private fun lazilyExpireIfNeeded(
        session: Session,
        now: Instant,
        traceId: String,
    ): Session {
        if (session.status != SessionStatus.ACTIVE || now.isBefore(session.expiresAt)) {
            return session
        }
        val expired = session.expire()
        repository.save(expired)
        publish(
            SessionExpired(meta(session.sessionId, session.tenantId, traceId), session.sessionId),
        )
        return expired
    }

    private fun findOrThrow(sessionId: SessionId): Session {
        val session = repository.findById(sessionId)
        return session ?: throw SessionNotFoundException(sessionId)
    }

    private fun publish(event: DomainEvent) = eventPublisher.publish(event)

    private fun meta(
        sessionId: SessionId,
        tenantId: TenantId,
        traceId: String,
    ): EventMetadata =
        EventMetadata(
            eventId = idGenerator.newId(),
            occurredAt = clock.now(),
            traceId = traceId,
            tenantId = tenantId,
            aggregateId = sessionId.value,
            version = 0,
        )
}
