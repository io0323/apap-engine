package apap.domain.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * P8後始末レビュー item3: `docs/design/12_ER図.md`で`tenant_id`を持つエンティティのRepository Portは、
 * 別テナントのデータへ到達できてしまう経路（[ConversationRepository]の`findById`/`appendTurn`が
 * テナント境界を検証していなかった実行経路上のバグ、[AliasRepository.save]が`tenantId`を
 * 受け取れなかった問題と同種）を将来再導入しないよう、検索・取得系メソッドの引数に
 * `TenantId`が含まれることを機械的に検証する。
 *
 * 対象外（意図的な除外、暗黙にしない）:
 * - `save`系（対象Aggregateが自身のtenantId込みで丸ごと渡されるため引数として不要）。
 * - [PolicyRepository.findEffective]の`tenantId`は`TenantId?`（PLATFORMスコープはtenant_idを
 *   持たないため、[docs/design/12_ER図.md]でもnullable）。
 * - [AliasRepository.listByModel]はテナント非依存の全体走査が正しい（`ModelManager.changeStatus`が
 *   Alias参照数をテナント横断で数える必要があるため。詳細はメソッド自身のKDoc参照）。
 * - [SessionRepository]はテナントを跨いだ境界チェックの対象外（詳細はそのKDoc参照。`findById`は
 *   「このsessionIdは誰のものか」を判定する入口そのものであり、事前にtenantIdを渡せない）。
 * - [BatchJobRepository.save]/`saveEvents`も同様に対象外（`findById`のみ検証対象）。
 */
class TenantScopedRepositoryTest {
    /** ファイル名 -> (検証対象メソッド名の集合、そのメソッドが要求するパラメータ型の正規表現) */
    private val expectedTenantScopedMethods =
        mapOf(
            "ConversationRepository.kt" to setOf("findById", "appendTurn", "findTurns", "delete"),
            "BatchJobRepository.kt" to setOf("findById"),
            "AliasRepository.kt" to setOf("findByName"),
            "MemoryRepository.kt" to setOf("searchByVector"),
            "QuotaPolicyRepository.kt" to setOf("findByTenant"),
            "QuotaSnapshotRepository.kt" to setOf("remaining"),
            "BudgetRepository.kt" to setOf("findByTenant"),
            "TenantEntitlementRepository.kt" to setOf("isPermitted"),
        )

    @Test
    fun `tenant-scoped repository query methods require a TenantId parameter`() {
        val repoRoot = findRepoRoot(File(".").canonicalFile)
        val portDir = File(repoRoot, "modules/apap-domain/src/main/kotlin/apap/domain/port")
        assertTrue(portDir.exists()) { "Portディレクトリが見つかりません: ${portDir.path}" }

        val violations = mutableListOf<String>()
        expectedTenantScopedMethods.forEach { (fileName, methodNames) ->
            val file = File(portDir, fileName)
            check(file.exists()) { "対象ファイルが見つかりません: ${file.path}" }
            val signatures = methodSignatures(file.readText())

            methodNames.forEach { methodName ->
                val signature = signatures[methodName]
                if (signature == null) {
                    violations += "$fileName.$methodName: メソッドが見つかりません（リネームされた場合はこのテストを更新すること）"
                } else if (!Regex("""tenantId\s*:\s*TenantId\??""").containsMatchIn(signature)) {
                    violations += "$fileName.$methodName: TenantId引数が見つかりません: $signature"
                }
            }
        }

        assertTrue(violations.isEmpty()) { violations.joinToString("\n") }
    }

    /** `fun name(...)`をシグネチャ全体（複数行にまたがる括弧を含む）として抽出する。 */
    private fun methodSignatures(source: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val declarationStart = Regex("""fun\s+(\w+)\s*\(""")
        declarationStart.findAll(source).forEach { match ->
            val name = match.groupValues[1]
            val parenStart = match.range.last
            var depth = 0
            var end = parenStart
            for (i in parenStart until source.length) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                if (depth == 0) {
                    end = i
                    break
                }
            }
            result[name] = source.substring(match.range.first, end + 1)
        }
        return result
    }

    private fun findRepoRoot(start: File): File {
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts が見つからず、リポジトリルートを特定できません（起点: $start）")
    }
}
