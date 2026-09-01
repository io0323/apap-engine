package apap.infrastructure.jdbc

import apap.domain.event.DomainEvent
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.Provider
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.provider.applyProviderEvent
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.ProviderId
import apap.domain.port.Clock
import apap.domain.port.EventStoreRepository
import apap.domain.port.IdGenerator
import apap.domain.port.ProviderRepository
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * [ProviderRepository]のJDBC実装（ADR-0025、ADR-0026）。
 *
 * `findById`/`findByStatus`/`findAll`は常に[EventStoreRepository]経由の再構築（[EventSourcedRepositorySupport]、
 * スナップショット＋差分イベント再生）を真の情報源とする（04_ドメイン設計.md 4.5）。`provider`/
 * `provider_endpoint`/`credential_ref`テーブルは横断検索（`findByStatus`のWHERE句等）のための
 * Read Model射影として`save`が同期的に書き込む（[JdbcUsageRepository]と同じ「監査/読み取り用の
 * 副産物」という発想。4.5は「Read Modelはイベント購読側が構築」とするが、ここではManagerの
 * 呼び出し元と同一トランザクション/呼び出しでの同期書き込みとした——要件充足に影響しない
 * 実装判断のためADR化せずここに根拠を記す）。
 *
 * `credential_ref_id`列（`CredentialRef`自身はid.を持たない）は[idGenerator]でULIDを都度発行する。
 */
@Suppress("TooManyFunctions")
class JdbcProviderRepository(
    private val dataSource: DataSource,
    private val eventStoreRepository: EventStoreRepository,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val objectMapper: ObjectMapper = JdbcSupport.objectMapper,
    snapshotEveryNEvents: Int = 100,
) : ProviderRepository {
    private val support =
        EventSourcedRepositorySupport(eventStoreRepository, Provider::class, ::applyProviderEvent, snapshotEveryNEvents)

    override fun findById(id: ProviderId): Provider? = support.reconstruct(id.value)

    override fun findByStatus(status: ProviderStatus): List<Provider> =
        idsWhere("status = ?") { it.setString(1, status.name) }.mapNotNull { support.reconstruct(it) }

    override fun findAll(): List<Provider> = idsWhere(null) {}.mapNotNull { support.reconstruct(it) }

    @Suppress("NestedBlockDepth")
    private fun idsWhere(
        whereClause: String?,
        bind: (java.sql.PreparedStatement) -> Unit,
    ): List<String> {
        val sql = "SELECT provider_id FROM provider" + (whereClause?.let { " WHERE $it" } ?: "")
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                bind(stmt)
                stmt.executeQuery().use { rs ->
                    val ids = mutableListOf<String>()
                    while (rs.next()) ids += rs.getString("provider_id")
                    return ids
                }
            }
        }
    }

    @Suppress("NestedBlockDepth", "LongMethod", "TooGenericExceptionCaught")
    override fun save(provider: Provider) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                upsertProvider(conn, provider)
                replaceEndpoints(conn, provider)
                replaceCredentialRefs(conn, provider)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    @Suppress("MagicNumber")
    private fun upsertProvider(
        conn: Connection,
        provider: Provider,
    ) {
        conn
            .prepareStatement(
                """
                INSERT INTO provider (
                    provider_id, name, adapter_plugin_id, spi_version, auth_type, priority, status,
                    rate_limit_rpm, rate_limit_tpm, rate_limit_concurrent, regions, tags, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                ON CONFLICT (provider_id) DO UPDATE SET
                    name = EXCLUDED.name, adapter_plugin_id = EXCLUDED.adapter_plugin_id,
                    spi_version = EXCLUDED.spi_version, auth_type = EXCLUDED.auth_type,
                    priority = EXCLUDED.priority, status = EXCLUDED.status,
                    rate_limit_rpm = EXCLUDED.rate_limit_rpm, rate_limit_tpm = EXCLUDED.rate_limit_tpm,
                    rate_limit_concurrent = EXCLUDED.rate_limit_concurrent, regions = EXCLUDED.regions,
                    tags = EXCLUDED.tags, updated_at = EXCLUDED.updated_at
                """.trimIndent(),
            ).use { stmt ->
                val now = Timestamp.from(clock.now())
                stmt.setString(1, provider.providerId.value)
                stmt.setString(2, provider.name)
                stmt.setString(3, provider.adapterPluginId)
                stmt.setString(4, provider.spiVersion.toString())
                stmt.setString(5, provider.authType)
                stmt.setInt(6, provider.priority)
                stmt.setString(7, provider.status.name)
                stmt.setInt(8, provider.rateLimits.rpm)
                stmt.setInt(9, provider.rateLimits.tpm)
                stmt.setInt(10, provider.rateLimits.concurrent)
                stmt.setString(11, objectMapper.writeValueAsString(provider.regions.map { it.code }))
                stmt.setString(12, objectMapper.writeValueAsString(provider.tags))
                stmt.setTimestamp(13, now)
                stmt.setTimestamp(14, now)
                stmt.executeUpdate()
            }
    }

    private fun replaceEndpoints(
        conn: Connection,
        provider: Provider,
    ) {
        conn.prepareStatement("DELETE FROM provider_endpoint WHERE provider_id = ?").use { stmt ->
            stmt.setString(1, provider.providerId.value)
            stmt.executeUpdate()
        }
        provider.endpoints.forEach { endpoint -> insertEndpoint(conn, provider.providerId.value, endpoint) }
    }

    @Suppress("MagicNumber")
    private fun insertEndpoint(
        conn: Connection,
        providerId: String,
        endpoint: Endpoint,
    ) {
        conn
            .prepareStatement(
                "INSERT INTO provider_endpoint (endpoint_id, provider_id, region, base_url, weight) " +
                    "VALUES (?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, endpoint.endpointId)
                stmt.setString(2, providerId)
                stmt.setString(3, endpoint.region.code)
                stmt.setString(4, endpoint.baseUrl)
                stmt.setInt(5, endpoint.weight)
                stmt.executeUpdate()
            }
    }

    private fun replaceCredentialRefs(
        conn: Connection,
        provider: Provider,
    ) {
        conn.prepareStatement("DELETE FROM credential_ref WHERE provider_id = ?").use { stmt ->
            stmt.setString(1, provider.providerId.value)
            stmt.executeUpdate()
        }
        provider.credentialRefs.forEach { credentialRef ->
            insertCredentialRef(conn, provider.providerId.value, credentialRef)
        }
    }

    @Suppress("MagicNumber")
    private fun insertCredentialRef(
        conn: Connection,
        providerId: String,
        credentialRef: CredentialRef,
    ) {
        conn
            .prepareStatement(
                "INSERT INTO credential_ref (credential_ref_id, provider_id, secret_ref, version, state, rotated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, idGenerator.newId())
                stmt.setString(2, providerId)
                stmt.setString(3, credentialRef.secretRef)
                stmt.setInt(4, credentialRef.version)
                stmt.setString(5, credentialRef.state.name)
                stmt.setTimestamp(6, Timestamp.from(clock.now()))
                stmt.executeUpdate()
            }
    }

    override fun saveEvents(
        id: ProviderId,
        events: List<DomainEvent>,
    ) {
        support.saveEvents(id.value, events)
    }
}
