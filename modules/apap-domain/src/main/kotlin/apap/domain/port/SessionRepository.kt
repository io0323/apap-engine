package apap.domain.port

import apap.domain.model.conversation.Session
import apap.domain.model.vo.SessionId

interface SessionRepository {
    fun findById(id: SessionId): Session?

    fun save(session: Session)

    fun expire(id: SessionId)
}
