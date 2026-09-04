package apap.domain.architecture

import org.junit.jupiter.api.Assertions.assertTrue
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
 *
 * ## adapters/ の扱い（P15で是正）
 *
 * 不変条件1は同時に「実Provider固有の知識は `adapters/` 配下にのみ存在してよい」とも定めている。
 * ところが走査ルートには `adapters` が含まれており、**実Provider向けAdapterを1つでも足すと
 * 必ず落ちる**状態だった。実Adapterが1つも無かったため、これまで矛盾が表面化していなかった。
 *
 * 「adapters/ を丸ごと対象外」にはしない——それでは adapter-mock まで無検査になり、
 * 「コアのテストは adapter-mock のみを使う」（不変条件1）の担保が消える。
 * config/vendor-neutrality/vendor-specific-adapters.txt に**そのAdapterだけ**を理由付きで
 * 登録し、登録されたディレクトリ配下のファイルのみ走査から外す。
 */
class VendorNeutralityTest {
    // "bin" はKonsistがスキャン時に生成する作業ディレクトリ（.gitignore対象、ソースではない）
    private val excludedDirNames = setOf("build", ".gradle", ".git", "bin")
    private val scannedRoots = listOf("modules", "gateway", "adapters", "integration")

    // "claude"はAI製品名として禁止語だが、本リポジトリの実装規約ファイル名（末尾".md"）への
    // 自己参照はAIベンダー名の言及ではないため誤検知として除外する。
    private val selfReferenceExclusion = Regex("""claude\.md""")

    // このメカニズム自体を説明するファイル（本ファイル）は、禁止語をデータとして扱う都合上
    // 除外パターンの正規表現リテラルなどが誤検知しうるため、走査対象から除く。
    private val selfFileName = "VendorNeutralityTest.kt"

    /** 例外を許すのは adapters/ 直下のディレクトリのみ。modules/ や gateway/ は例外にできない。 */
    private val vendorSpecificRoot = "adapters"

    @Test
    fun `modules and gateway sources contain no forbidden vendor or model names`() {
        val repoRoot = findRepoRoot(File(".").canonicalFile)
        // 走査ルートが全モジュールを覆っているか（対象0件ではなく「モジュールごと対象外」の検出）。
        ModuleScanCoverage.assertScanCoversAllModules("VendorNeutralityTest", repoRoot, scannedRoots)
        val forbiddenTerms = loadForbiddenTerms(File(repoRoot, "config/vendor-neutrality/forbidden-terms.txt"))

        val exemptedDirs = loadVendorSpecificAdapters(repoRoot)

        val existingRoots = scannedRoots.map { File(repoRoot, it) }.filter { it.exists() }
        val scannedFileCount = existingRoots.sumOf { root -> countScannedFiles(root, exemptedDirs) }

        // スキャン対象が0件だとテストは違反なしで沈黙成功する。スコープ取得の失敗を
        // 規約違反と同様に「テストが落ちる」状態にする（Konsistベースの
        // アーキテクチャテストと同じ理由。ArchitectureScopeGuard.kt参照）。
        assertTrue(
            scannedFileCount > 0,
            "Vendor Neutralityスキャン対象が0件です（対象: $scannedRoots）。" +
                "この状態では違反を検出できません。",
        )

        val violations = existingRoots.flatMap { findViolations(it, repoRoot, forbiddenTerms, exemptedDirs) }

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
        exemptedDirs: List<File>,
    ): List<String> =
        root
            .walkTopDown()
            .onEnter { dir -> dir.name !in excludedDirNames && dir !in exemptedDirs }
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

    private fun countScannedFiles(
        root: File,
        exemptedDirs: List<File>,
    ): Int =
        root
            .walkTopDown()
            .onEnter { dir -> dir.name !in excludedDirNames && dir !in exemptedDirs }
            .count { it.isFile }

    /**
     * 実Provider固有の知識を持ってよいAdapterディレクトリを読む。
     *
     * 「理由必須」「実在必須」「adapters/直下のみ」の3点を検査する。緩めると、この仕組み自体が
     * 不変条件1の抜け穴になる——理由の無い除外は漏れと区別できず、実在しないパスの除外は
     * 消したモジュールの設定が残り続け、パス制限が無ければ modules/ を丸ごと除外できてしまう。
     */
    private fun loadVendorSpecificAdapters(repoRoot: File): List<File> {
        val file = File(repoRoot, "config/vendor-neutrality/vendor-specific-adapters.txt")
        check(file.exists()) { "Vendor固有Adapterの許可リストが見つかりません: ${file.path}" }
        return file
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line -> parseVendorSpecificEntry(line, repoRoot) }
    }

    private fun parseVendorSpecificEntry(
        line: String,
        repoRoot: File,
    ): File {
        val parts = line.split("|", limit = 2)
        val path = parts[0].trim()
        val reason = parts.getOrNull(1)?.trim().orEmpty()
        assertTrue(
            reason.isNotBlank(),
            "vendor-specific-adapters.txt: \"$path\" に理由がありません。" +
                "理由を書けない除外は「漏れ」と区別できないため許可しません（`<path> | <理由>` 形式）。",
        )
        assertTrue(
            path.startsWith("$vendorSpecificRoot/") && path.count { it == '/' } == 1,
            "vendor-specific-adapters.txt: \"$path\" は $vendorSpecificRoot/ 直下のディレクトリではありません。" +
                "例外にできるのは実Provider向けAdapterのモジュールだけです" +
                "（modules/ や gateway/ を除外できてしまうと不変条件1の抜け穴になる）。",
        )
        val dir = File(repoRoot, path)
        assertTrue(
            dir.isDirectory,
            "vendor-specific-adapters.txt: \"$path\" が実在しません。" +
                "削除したモジュールの除外が残ると、後から同名のディレクトリが無検査で復活します。",
        )
        return dir
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
