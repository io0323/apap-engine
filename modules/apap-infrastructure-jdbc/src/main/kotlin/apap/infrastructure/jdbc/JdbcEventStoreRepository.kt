package apap.infrastructure.jdbc

import apap.domain.event.DomainEvent
import apap.domain.model.AggregateSnapshot
import apap.domain.port.Clock
import apap.domain.port.EventStoreRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource
import kotlin.reflect.KClass

/** ADR-0014が要求する楽観ロック違反を、apap-testkitのIn-Memory実装と同じ例外・メッセージ形式で表面化させる。 */
class EventStreamConcurrencyException(
    streamId: String,
    expectedVersion: Long,
    actualVersion: Long,
) : IllegalStateException(
        "Concurrent append to stream $streamId: expectedVersion=$expectedVersion, actualVersion=$actualVersion",
    )

/**
 * [EventStoreRepository]のJDBC実装（ADR-0025: PostgreSQL、素のJDBC）。
 *
 * 楽観ロックは`(stream_id, version)`のPRIMARY KEY制約そのものに委ねる（読取→比較→書込という
 * 競合の余地がある事前チェックではなく、DBの一意制約違反を確定的な競合シグナルとして使う。
 * `SELECT MAX(version)`で先に比較してからINSERTする方式は、その2ステップの間に別トランザクションが
 * 割り込む余地があり、真の意味での楽観ロックにならないため意図的に避けた——要件充足に影響しない
 * 実装判断のためADR化せずここに根拠を記す）。
 *
 * イベントの型復元は`event_type`列（イベントクラスの単純名、[DomainEventCoverageTest]が閉じた集合と
 * して検証している`apap.domain.event`パッケージ内のクラス）から`Class.forName`で解決する。
 * 新規イベント追加時にこの実装を手動更新する必要がないようにするための設計判断。
 */
class JdbcEventStoreRepository(
    private val dataSource: DataSource,
    private val clock: Clock,
    private val objectMapper: ObjectMapper = JdbcSupport.objectMapper,
) : EventStoreRepository {
    // NestedBlockDepth: try/commit/rollback around prepareStatement.use is inherent to raw JDBC.
    // TooGenericExceptionCaught: any failure, not only PSQLException, must trigger conn.rollback().
    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
    override fun append(
        streamId: String,
        events: List<DomainEvent>,
        expectedVersion: Long,
    ) {
        if (events.isEmpty()) return
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                events.forEachIndexed { offset, event ->
                    insertEvent(conn, streamId, expectedVersion + offset + 1, event)
                }
                conn.commit()
            } catch (e: PSQLException) {
                conn.rollback()
                if (isUniqueViolation(e)) {
                    throw EventStreamConcurrencyException(streamId, expectedVersion, latestVersion(streamId))
                }
                throw e
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    @Suppress("MagicNumber") // JDBC positional parameter indices, not meaningful as named constants.
    private fun insertEvent(
        conn: Connection,
        streamId: String,
        version: Long,
        event: DomainEvent,
    ) {
        conn
            .prepareStatement(
                """
                INSERT INTO event_store (stream_id, version, event_type, payload, occurred_at, trace_id)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, streamId)
                stmt.setLong(2, version)
                stmt.setString(3, event::class.simpleName)
                stmt.setString(4, objectMapper.writeValueAsString(event))
                stmt.setTimestamp(5, Timestamp.from(event.meta.occurredAt))
                stmt.setString(6, event.meta.traceId)
                stmt.executeUpdate()
            }
    }

    private fun isUniqueViolation(e: PSQLException): Boolean = e.sqlState == POSTGRES_UNIQUE_VIOLATION_SQLSTATE

    @Suppress("NestedBlockDepth") // connection/statement/resultset/while is inherent to raw JDBC row iteration.
    override fun read(
        streamId: String,
        fromVersion: Long,
    ): List<DomainEvent> {
        val sql =
            "SELECT event_type, payload FROM event_store " +
                "WHERE stream_id = ? AND version >= ? ORDER BY version ASC"
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(sql)
                .use { stmt ->
                    stmt.setString(1, streamId)
                    stmt.setLong(2, fromVersion.coerceAtLeast(1))
                    stmt.executeQuery().use { rs ->
                        val events = mutableListOf<DomainEvent>()
                        while (rs.next()) {
                            events += deserializeEvent(rs.getString("event_type"), rs.getString("payload"))
                        }
                        return events
                    }
                }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun deserializeEvent(
        eventType: String,
        payload: String,
    ): DomainEvent {
        val eventClass = Class.forName("apap.domain.event.$eventType") as Class<out DomainEvent>
        return objectMapper.readValue(payload, eventClass)
    }

    @Suppress("NestedBlockDepth") // connection/statement/resultset is inherent to raw JDBC.
    override fun latestVersion(streamId: String): Long {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT MAX(version) FROM event_store WHERE stream_id = ?").use { stmt ->
                stmt.setString(1, streamId)
                stmt.executeQuery().use { rs ->
                    return if (rs.next()) rs.getLong(1).let { if (rs.wasNull()) 0L else it } else 0L
                }
            }
        }
    }

    @Suppress("MagicNumber") // JDBC positional parameter indices, not meaningful as named constants.
    override fun <T : Any> saveSnapshot(snapshot: AggregateSnapshot<T>) {
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO event_store_snapshot (stream_id, version, state_type, state, saved_at)
                    VALUES (?, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (stream_id) DO UPDATE SET
                        version = EXCLUDED.version, state_type = EXCLUDED.state_type,
                        state = EXCLUDED.state, saved_at = EXCLUDED.saved_at
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, snapshot.streamId)
                    stmt.setLong(2, snapshot.version)
                    stmt.setString(3, snapshot.state::class.java.name)
                    stmt.setString(4, objectMapper.writeValueAsString(snapshot.state))
                    stmt.setTimestamp(5, Timestamp.from(clock.now()))
                    stmt.executeUpdate()
                }
        }
    }

    @Suppress("NestedBlockDepth") // connection/statement/resultset is inherent to raw JDBC.
    override fun <T : Any> loadSnapshot(
        streamId: String,
        stateType: KClass<T>,
    ): AggregateSnapshot<T>? {
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT version, state_type, state FROM event_store_snapshot WHERE stream_id = ?",
                ).use { stmt ->
                    stmt.setString(1, streamId)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return null
                        return toSnapshot(rs, streamId, stateType)
                    }
                }
        }
    }

    private fun <T : Any> toSnapshot(
        rs: ResultSet,
        streamId: String,
        stateType: KClass<T>,
    ): AggregateSnapshot<T> {
        val storedType = rs.getString("state_type")
        require(storedType == stateType.java.name) {
            "Stored snapshot for stream $streamId has state_type=$storedType, requested ${stateType.java.name}"
        }
        val state = objectMapper.readValue(rs.getString("state"), stateType.java)
        return AggregateSnapshot(streamId, rs.getLong("version"), state)
    }

    private companion object {
        const val POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505"
    }
}
