package apap.domain.architecture

import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 01_CLAUDE.md 不変条件1: コード・設定・テスト・コメントに特定AI Provider名/製品名/
 * モデル名を一切書かない（Vendor Neutral）。
 *
 * Konsistの宣言解析は「コメント」や「YAML等の非Kotlinファイル」までは対象にできないため、
 * ここでは modules/ と gateway/ 配下の全ファイルをテキストとして走査する。
 * 禁止語リストは config/vendor-neutrality/forbidden-terms.txt で管理し、行追加のみで拡張できる。
 */
class VendorNeutralityTest {
    // "bin" はKonsistがスキャン時に生成する作業ディレクトリ（.gitignore対象、ソースではない）
    private val excludedDirNames = setOf("build", ".gradle", ".git", "bin")
    private val scannedRoots = listOf("modules", "gateway")

    // "claude"はAI製品名として禁止語だが、本リポジトリの実装規約ファイル名（末尾".md"）への
    // 自己参照はAIベンダー名の言及ではないため誤検知として除外する。
    private val selfReferenceExclusion = Regex("""claude\.md""")

    // このメカニズム自体を説明するファイル（本ファイル）は、禁止語をデータとして扱う都合上
    // 除外パターンの正規表現リテラルなどが誤検知しうるため、走査対象から除く。
    private val selfFileName = "VendorNeutralityTest.kt"

    @Test
    fun `modules and gateway sources contain no forbidden vendor or model names`() {
        val repoRoot = findRepoRoot(File(".").canonicalFile)
        val forbiddenTerms = loadForbiddenTerms(File(repoRoot, "config/vendor-neutrality/forbidden-terms.txt"))

        val violations =
            scannedRoots
                .map { File(repoRoot, it) }
                .filter { it.exists() }
                .flatMap { findViolations(it, repoRoot, forbiddenTerms) }

        if (violations.isNotEmpty()) {
            fail<Unit>(
                "Vendor Neutral違反を検出しました（01_CLAUDE.md 不変条件1）:\n" +
                    violations.joinToString("\n"),
            )
        }
    }

    private fun findViolations(
        root: File,
        repoRoot: File,
        forbiddenTerms: List<String>,
    ): List<String> =
        root
            .walkTopDown()
            .onEnter { dir -> dir.name !in excludedDirNames }
            .filter { it.isFile }
            .flatMap { file -> violationsInFile(file, repoRoot, forbiddenTerms) }
            .toList()

    private fun violationsInFile(
        file: File,
        repoRoot: File,
        forbiddenTerms: List<String>,
    ): List<String> {
        val rawLowerText =
            if (file.name == selfFileName) {
                null
            } else {
                runCatching { file.readText() }.getOrNull()?.lowercase()
            }
        val lowerText = rawLowerText?.replace(selfReferenceExclusion, "") ?: return emptyList()
        return forbiddenTerms
            .filter { term -> lowerText.contains(term) }
            .map { term -> "${file.relativeTo(repoRoot)}: contains forbidden term \"$term\"" }
    }

    private fun findRepoRoot(start: File): File {
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts が見つからず、リポジトリルートを特定できません（起点: $start）")
    }

    private fun loadForbiddenTerms(file: File): List<String> {
        check(file.exists()) { "禁止語リストが見つかりません: ${file.path}" }
        return file
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.lowercase() }
    }
}
