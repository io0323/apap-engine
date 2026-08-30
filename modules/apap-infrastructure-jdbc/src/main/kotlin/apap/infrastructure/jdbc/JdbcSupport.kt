package apap.infrastructure.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import javax.sql.DataSource

/**
 * ADR-0025: このモジュールが依存するのは`javax.sql.DataSource`のみ（DIコンテナ・アプリフレームワークは
 * 持ち込まない、CLAUDE.md不変条件6）。埋込ホストが自前のDataSource/コネクションプールを持つ場合は
 * それをそのまま各Repositoryへ渡してよく、[dataSource]はホストがプール実装を持たない場合の
 * 最小限の既定実装（プーリングなし、`org.postgresql.ds.PGSimpleDataSource`）として提供する。
 */
object JdbcSupport {
    fun dataSource(
        host: String = "localhost",
        port: Int = DEFAULT_PORT,
        database: String = "apap",
        user: String = "apap",
        password: String = "apap",
    ): DataSource =
        PGSimpleDataSource().apply {
            serverNames = arrayOf(host)
            portNumbers = intArrayOf(port)
            databaseName = database
            this.user = user
            this.password = password
        }

    /** `V1__initial_schema.sql`（このモジュールの`db/migration`配下）をこのクラスパスから適用する。 */
    fun migrate(dataSource: DataSource) {
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    /**
     * ADR-0017: Jacksonに一本化。Kotlin data class（プライマリコンストラクタ/デフォルト値）を正しく
     * 扱うためkotlin-moduleを、`java.time.Instant`等JSR-310型を扱うためJavaTimeModuleを登録する。
     */
    val objectMapper: ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())

    private const val DEFAULT_PORT = 5432
}
