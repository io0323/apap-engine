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
            "UsageRepository.kt" to setOf("aggregate"),
        )

    /**
     * `TenantId`に言及するがテナント境界の引数検証対象**ではない**Portと、その理由。
     * 理由を書けない除外は「検査し忘れ」と区別できないため、空文字は許可しない
     * （`ModuleScanCoverage.ScanExclusion`と同じ考え方）。
     */
    private val exclusions =
        mapOf(
            "MetricsRecorder.kt" to
                "Repositoryではなく計測用Port。TenantIdはメトリクスラベルとして受け取るのみで、" +
                "他テナントのデータへ到達する読み取り経路を持たない",
            "PolicyRepository.kt" to
                "findEffectiveのtenantIdはTenantId?（PLATFORMスコープはtenant_idを持たないため、" +
                "12_ER図.mdでもnullable）。非nullを強制すると設計と矛盾する",
            "SessionRepository.kt" to
                "findByIdは「このsessionIdは誰のものか」を判定する入口そのものであり、" +
                "事前にtenantIdを渡せない（そのKDoc参照）",
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

    /**
     * 検査対象リストそのものの網羅性を検証する。
     *
     * 対象ファイル名を手で書き並べる方式は、**新しいテナント境界付きPortを足したときに
     * 黙って対象外になる**（`scannedRoots`にintegrationが無かったのと同じ失敗の形）。
     * `TenantId`に言及するPortは、検証対象か、理由付きの除外か、必ずどちらかであることを強制する。
     */
    @Test
    fun `every port mentioning TenantId is either checked or excluded with a reason`() {
        val repoRoot = findRepoRoot(File(".").canonicalFile)
        val portDir = File(repoRoot, "modules/apap-domain/src/main/kotlin/apap/domain/port")
        val portFiles = portDir.listFiles { f: File -> f.extension == "kt" }?.toList().orEmpty()

        assertTrue(portFiles.isNotEmpty()) {
            "Portファイルを1件も読み取れませんでした（$portDir）。この状態では網羅性を検証できません。"
        }

        exclusions.forEach { (fileName, reason) ->
            assertTrue(reason.isNotBlank()) { "$fileName: 除外理由が空です" }
            assertTrue(File(portDir, fileName).exists()) {
                "$fileName: 除外に書かれていますが実在しません（改名・削除の取り残し）"
            }
        }

        val mentioningTenantId =
            portFiles.filter { it.readText().contains("TenantId") }.map { it.name }.sorted()
        val accountedFor = expectedTenantScopedMethods.keys + exclusions.keys
        val unaccounted = mentioningTenantId.filterNot { it in accountedFor }

        assertTrue(unaccounted.isEmpty()) {
            "TenantIdに言及するのに、テナント境界検査の対象にも除外にも入っていないPortがあります:\n" +
                unaccounted.joinToString("\n") { "  - $it" } +
                "\n対処: expectedTenantScopedMethodsへ検証対象メソッドを足すか、exclusionsへ理由付きで宣言してください。"
        }

        // 対象・除外の側に、TenantIdへ言及しなくなった取り残しが無いこと。
        val stale = accountedFor.filterNot { it in mentioningTenantId }
        assertTrue(stale.isEmpty()) {
            "TenantIdへ言及しなくなったPortが検査対象/除外に残っています: $stale"
        }
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
