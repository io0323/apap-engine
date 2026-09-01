package apap.infrastructure.persistence.inmemory

import apap.domain.model.conversation.Session
import apap.domain.model.vo.SessionId
import apap.domain.port.SessionRepository
import java.util.concurrent.ConcurrentHashMap

/** [SessionRepository]の本番用In-Memory実装。 */
class InMemorySessionRepository : SessionRepository {
    private val sessions = ConcurrentHashMap<SessionId, Session>()

    override fun findById(id: SessionId): Session? = sessions[id]

    override fun save(session: Session) {
        sessions[session.sessionId] = session
    }

    override fun expire(id: SessionId) {
        sessions.computeIfPresent(id) { _, session -> session.expire() }
    }
}
