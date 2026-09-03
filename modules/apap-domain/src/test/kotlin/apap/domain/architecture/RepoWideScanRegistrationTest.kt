package apap.domain.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 「リポジトリ全体を走査する検査」が[ModuleScanCoverage]への登録を忘れていないことを検証する。
 *
 * [ModuleScanCoverage]は各検査が自分で呼ぶ方式（分散）にしている。走査ルートを検査側の変数から
 * そのまま渡せるため、中央の登録簿に値を書き写す方式と違って二重管理にならない利点がある一方、
 * **新しい検査が呼び忘れる**という抜け道ができる。ここはその抜け道を塞ぐ。
 *
 * 判定は構文で行う: `scannedRoots` という名前の走査ルート変数を宣言しているテストファイルは、
 * 同じファイル内で`assertScanCoversAllModules`を呼んでいなければならない。命名規約に依存するため、
 * 別名（`roots`等）で書けば回避できてしまうが、それは意図的な迂回であり、
 * 規約として`scannedRoots`に統一していることを本テストのメッセージで示す。
 */
class RepoWideScanRegistrationTest {
    private val excludedDirNames = setOf("build", ".gradle", ".git", "bin")

    @Test
    fun `every repo-wide scan registers its scanned roots with ModuleScanCoverage`() {
        val repoRoot = ModuleScanCoverage.findRepoRoot(File(".").canonicalFile)
        val testFiles = kotlinTestSources(repoRoot)

        assertTrue(
            testFiles.isNotEmpty(),
            "テストソースを1件も走査できませんでした。この状態では登録漏れを検出できません。",
        )

        val declaringFiles = testFiles.filter { SCANNED_ROOTS_DECLARATION.containsMatchIn(it.readText()) }

        // 本テスト自身が機能していることの担保: 既知のリポジトリ全体走査が1件も見つからないなら、
        // 検出方法（正規表現・走査対象）が壊れている。
        assertTrue(
            declaringFiles.size >= KNOWN_REPO_WIDE_SCAN_COUNT,
            "リポジトリ全体を走査する検査が${declaringFiles.size}件しか見つかりませんでした" +
                "（既知は${KNOWN_REPO_WIDE_SCAN_COUNT}件以上）。検出方法が壊れている可能性があります。",
        )

        val unregistered =
            declaringFiles
                .filterNot { it.readText().contains("assertScanCoversAllModules") }
                .map { it.relativeTo(repoRoot).path }

        if (unregistered.isNotEmpty()) {
            fail<Unit>(
                "走査ルート（scannedRoots）を持つのに、対象範囲の検証を登録していない検査があります:\n" +
                    unregistered.joinToString("\n") { "  - $it" } +
                    "\n対処: 検証本体の前に " +
                    "ModuleScanCoverage.assertScanCoversAllModules(<検査名>, repoRoot, scannedRoots) を呼び、" +
                    "対象外にするモジュールはScanExclusionで理由付きに宣言してください。",
            )
        }
    }

    private fun kotlinTestSources(repoRoot: File): List<File> =
        repoRoot
            .walkTopDown()
            .onEnter { it.name !in excludedDirNames }
            .filter { it.isFile && it.extension == "kt" }
            .filter { "/src/test" in it.path.replace(File.separatorChar, '/') }
            .toList()

    private companion object {
        /** `private val scannedRoots = listOf(...)` 形式の走査ルート宣言。 */
        val SCANNED_ROOTS_DECLARATION = Regex("""\bval\s+scannedRoots\s*=""")

        /**
         * 2026-09-02時点でリポジトリ全体を走査する検査は3件
         * （VendorNeutralityTest / ClockAndIdGeneratorDirectCallTest / TestMethodReturnTypeTest）。
         * 減った場合は検出方法の破損を疑う。増える分には問題ない。
         */
        const val KNOWN_REPO_WIDE_SCAN_COUNT = 3
    }
}
