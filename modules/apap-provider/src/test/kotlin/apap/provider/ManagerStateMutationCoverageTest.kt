package apap.provider

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * P8後始末レビュー item2: `decide()`層が無く、`ProviderManager`/`ModelManager`はstate保存
 * （`xxxRepository.save(...)`）とイベント発行を別々に行う構造のため、新しいstate変更メソッドを
 * 追加した際にイベント発行（ひいては[ProviderManagerEventRoundTripTest]/[ModelManagerEventRoundTripTest]
 * によるround-trip検証）を追加し忘れても、コンパイル・既存テストのどちらも失敗しない
 * （実際に`ModelManager`がEvent Storeへ一度も書いていなかったバグが、この方法でしか検出できない
 * 形で存在していた）。
 *
 * そのため、`xxxRepository.save(`を直接呼ぶメソッド名の集合を[VendorNeutralityTest]と同様の
 * テキストスキャンで機械的に洗い出し、ここで管理する期待集合とクローズドセットとして突き合わせる。
 * 新しいstate変更メソッドが追加されて期待集合に無ければこのテストが落ちる——追加した開発者は
 * 期待集合を更新すると同時に、round-tripテストへ対応するシナリオを追加すること
 * （このテスト自体はround-tripが実際に書かれたことまでは検証できないが、少なくとも
 * 「気づかれずに漏れる」ことを防ぐ）。
 */
class ManagerStateMutationCoverageTest {
    private val expectedStateMutatingMethods =
        mapOf(
            "ProviderManager.kt" to
                setOf(
                    "register",
                    "beginValidation",
                    "passValidation",
                    "failValidation",
                    "enable",
                    "drain",
                    "completeDraining",
                    "delete",
                ),
            "ModelManager.kt" to
                setOf(
                    "register",
                    "changeStatus",
                    "assignAlias",
                ),
        )

    @Test
    fun `every method that persists new Provider or Model Alias state is accounted for`() {
        val repoRoot = findRepoRoot(File(".").canonicalFile)
        val violations = mutableListOf<String>()

        expectedStateMutatingMethods.forEach { (fileName, expected) ->
            val file =
                File(repoRoot, "modules/apap-provider/src/main/kotlin/apap/provider/$fileName")
            check(file.exists()) { "対象ファイルが見つかりません: ${file.path}" }
            val actual = stateMutatingMethodNames(file.readText())

            val missing = expected - actual
            val extra = actual - expected
            if (missing.isNotEmpty()) {
                violations += "$fileName: 期待していたが見つからないstate変更メソッド: $missing"
            }
            if (extra.isNotEmpty()) {
                violations +=
                    "$fileName: 未知のstate変更メソッドを検出: $extra " +
                    "（新規追加なら、このテストの期待集合とround-tripテストの両方を更新すること）"
            }
        }

        assertTrue(violations.isEmpty()) { violations.joinToString("\n") }
    }

    /**
     * `fun`宣言行（`private`/`suspend`修飾子を許容）を境界として本文を分割し、
     * `.save(`（xxxRepository.save）を直接呼ぶメソッド名を集める。ネストしたラムダ内の`fun`は
     * 対象クラスに存在しないため無視してよい（両ファイルとも当てはまらない）。
     */
    private fun stateMutatingMethodNames(source: String): Set<String> {
        val funDeclaration = Regex("""^ {4}(?:private |internal )?(?:suspend )?fun (\w+)\(""")
        val lines = source.lines()
        val boundaries =
            lines
                .mapIndexedNotNull { index, line -> funDeclaration.find(line)?.let { index to it.groupValues[1] } }
        return boundaries
            .mapIndexed { i, (startLine, name) ->
                val endLine = boundaries.getOrNull(i + 1)?.first ?: lines.size
                name to lines.subList(startLine, endLine).joinToString("\n")
            }.filter { (_, body) -> Regex("""\w+Repository\.save\(""").containsMatchIn(body) }
            .map { (name, _) -> name }
            .toSet()
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
