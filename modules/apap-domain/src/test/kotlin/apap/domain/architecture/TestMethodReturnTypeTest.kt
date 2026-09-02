package apap.domain.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * JUnit 5は戻り値型が`Unit`（Javaの`void`）でないメソッドをテストとして実行しない。Kotlinの
 * 式本体（`fun \`name\`() = runBlocking { ... }`）は最後の式の型が推論されるため、末尾が
 * `assertThrows(...)`（`Throwable`を返す）等になっていると**戻り値型がUnitでなくなり、
 * そのテストは失敗も成功もせず単に実行されない**。ビルドは緑のままなので気づけない。
 *
 * 実際にこの状態が本リポジトリに存在した（P9レビュー時に検出）。末尾が`assertThrows(...)`で
 * 式本体だったため一度も実行されていなかったのは次の3件:
 * - `ApapEngineBuilderTest.close rejects new requests and is idempotent`
 * - `ProviderManagerTest.completeValidation reverts to REGISTERED ...`
 * - `StreamingEngineTest.failure before the first chunk is thrown for the caller to fall back`
 *
 * いずれも「実装済み・matrix上は検証済み」と扱われながら実行されていなかった。修正後は3件とも
 * 実行され、いずれも成功する（テスト自体は最初から正しく、走っていなかっただけ）。
 *
 * `ArchitectureScopeGuard`と同じ思想（空スコープ＝沈黙成功を許さない）で、構文上の規約
 * 「`@Test`関数は必ずブロック本体にするか、明示的に`: Unit`を書く」を機械検証する。
 * 型推論に頼らず構文だけで判定できるため、テキストスキャンで足りる。
 */
class TestMethodReturnTypeTest {
    private val excludedDirNames = setOf("build", ".gradle", ".git", "bin")
    private val scannedRoots = listOf("modules", "gateway", "adapters")

    @Test
    fun `every @Test function has a block body or an explicit Unit return type`() {
        val repoRoot = findRepoRoot(File(".").canonicalFile)
        val testSources =
            scannedRoots
                .map { File(repoRoot, it) }
                .filter { it.exists() }
                .flatMap { root -> testSourceKotlinFiles(root) }

        assertTrue(
            testSources.isNotEmpty(),
            "@Test戻り値型スキャンの対象が0件です（対象: $scannedRoots のsrc/test）。" +
                "この状態では違反を検出できません（ArchitectureScopeGuard.ktと同じ理由）。",
        )

        val violations = testSources.flatMap { file -> violationsInFile(file, repoRoot) }

        if (violations.isNotEmpty()) {
            fail<Unit>(
                "戻り値型がUnitでない可能性のある@Test関数を検出しました。JUnit 5はこれらを" +
                    "実行せず、ビルドは緑のまま「一度も走っていないテスト」になります。" +
                    "ブロック本体にするか、明示的に`: Unit`を付けてください:\n" +
                    violations.joinToString("\n"),
            )
        }
    }

    private fun testSourceKotlinFiles(root: File): List<File> =
        root
            .walkTopDown()
            .onEnter { dir -> dir.name !in excludedDirNames }
            .filter { it.isFile && it.extension == "kt" && "/src/test" in it.path.replace(File.separatorChar, '/') }
            .toList()

    private fun violationsInFile(
        file: File,
        repoRoot: File,
    ): List<String> {
        val lines = stripComments(file.readText()).lines()
        val violations = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            if (!line.contains("@Test")) return@forEachIndexed
            val declaration = functionDeclarationAfter(lines, index) ?: return@forEachIndexed
            if (declaration.text.usesExpressionBodyWithoutExplicitUnit()) {
                violations += "${file.relativeTo(repoRoot)}:${declaration.lineNumber}: ${declaration.text.trim()}"
            }
        }
        return violations
    }

    private data class Declaration(
        val lineNumber: Int,
        val text: String,
    )

    /**
     * [index]行の`@Test`に続く関数宣言を、パラメータリストの括弧の対応が取れるまで連結して返す
     * （パラメータが複数行に分かれる宣言に対応するため）。
     */
    private fun functionDeclarationAfter(
        lines: List<String>,
        index: Int,
    ): Declaration? {
        val searchRange = index until minOf(index + MAX_ANNOTATION_LINES, lines.size)
        val start = searchRange.firstOrNull { lines[it].contains("fun ") } ?: return null
        val builder = StringBuilder()
        var depth = 0
        var seenOpen = false
        for (i in start until minOf(start + MAX_SIGNATURE_LINES, lines.size)) {
            val line = lines[i]
            builder.append(line).append(' ')
            depth += line.count { it == '(' }
            seenOpen = seenOpen || line.contains('(')
            depth -= line.count { it == ')' }
            if (seenOpen && depth <= 0) break
        }
        return Declaration(start + 1, builder.toString())
    }

    /** パラメータリストの閉じ括弧の直後が`=`（式本体）で、かつ`: Unit`が書かれていない。 */
    private fun String.usesExpressionBodyWithoutExplicitUnit(): Boolean {
        val closeIndex = lastIndexOfMatchingClose()
        val tail = closeIndex?.let { substring(it + 1).trim() } ?: return false
        return tail.startsWith("=") && !tail.startsWith(": Unit") && !tail.startsWith(":Unit")
    }

    private fun String.lastIndexOfMatchingClose(): Int? {
        val open = indexOf('(')
        var depth = 0
        var result: Int? = null
        if (open >= 0) {
            for (i in open until length) {
                when (this[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                if (depth == 0) {
                    result = i
                    break
                }
            }
        }
        return result
    }

    private fun stripComments(text: String): String =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    private fun findRepoRoot(start: File): File {
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts が見つからず、リポジトリルートを特定できません（起点: $start）")
    }

    private companion object {
        /** `@Test`から関数宣言行までに挟まりうる他のアノテーション行数の上限。 */
        const val MAX_ANNOTATION_LINES = 6

        /** 関数シグネチャが折り返される行数の上限。 */
        const val MAX_SIGNATURE_LINES = 20
    }
}
