package apap.testkit.inmemory

import apap.domain.model.conversation.Session
import apap.domain.model.vo.SessionId
import apap.domain.port.SessionRepository

class InMemorySessionRepository : SessionRepository {
    private val sessions = mutableMapOf<SessionId, Session>()

    override fun findById(id: SessionId): Session? = sessions[id]

    override fun save(session: Session) {
        sessions[session.sessionId] = session
    }

    override fun expire(id: SessionId) {
        sessions[id]?.let { sessions[id] = it.expire() }
    }
}
