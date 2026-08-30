package apap.infrastructure.jdbc

import javax.sql.DataSource

/**
 * `docker compose -f tools/docker-compose.yaml up -d rdbms`（CLAUDE.md「コマンド」参照）で起動した
 * ローカルPostgreSQLに接続する。このモジュールのテストは実DBを要求する統合テストであり、
 * DBが起動していない環境では失敗する（H2等の代替は使わない——このモジュールの存在理由自体が
 * 「PostgreSQL固有の方言・pgvector拡張を実際に検証すること」であるため、代替エンジンでは
 * 検証の意味が薄れる。要件充足に影響しない実装判断のためADR化せずここに根拠を記す）。
 */
object JdbcTestSupport {
    fun freshDataSource(): DataSource {
        val dataSource = JdbcSupport.dataSource()
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;")
            }
        }
        JdbcSupport.migrate(dataSource)
        return dataSource
    }
}
