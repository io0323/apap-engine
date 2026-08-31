package apap.domain.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * CLAUDE.md実装規約: 時刻とIDはPort化して注入する（テストの決定性のため`Instant.now()`/
 * `System.currentTimeMillis()`/`LocalDateTime.now()`/`LocalDate.now()`/`UUID.randomUUID()`の
 * 直接呼び出しを禁止）。この規約はドキュメントのみで機械検証されておらず、実際に
 * `JdbcEventStoreRepository.saveSnapshot`で違反していた（コミット65c4594で個別修正）。
 * 再発防止のためここで機械検証する。
 *
 * [VendorNeutralityTest]と同じ理由（複数モジュールを横断してテキストとして走査する必要がある）で
 * Konsistの宣言解析ではなくテキストスキャンを用いる。ただしKDocで対象API名を「禁止パターンの説明」
 * として言及すること自体（[apap.domain.port.Clock]/[apap.domain.port.IdGenerator]自身のKDocが
 * まさにそうしている）は許容したいため、コメントを除去してから判定する。
 *
 * 検査対象は各モジュールの`src/main`のみとし、`src/test`はテストフィクスチャ構築での使用を許容する
 * （テストの決定性という規約の趣旨は本番コードの挙動に関するものであり、この除外は暗黙にせず
 * ここに明示する）。
 */
class ClockAndIdGeneratorDirectCallTest {
    private val excludedDirNames = setOf("build", ".gradle", ".git", "bin")
    private val scannedRoots = listOf("modules", "gateway")

    private val forbiddenPatterns =
        listOf(
            Regex("""\bInstant\.now\("""),
            Regex("""\bSystem\.currentTimeMillis\("""),
            Regex("""\bLocalDateTime\.now\("""),
            Regex("""\bLocalDate\.now\("""),
            Regex("""\bUUID\.randomUUID\("""),
        )

    /**
     * Clock/IdGenerator Port自体の実装クラスは実際のシステム時刻・乱数を使わなければならないため
     * 許可する。リポジトリルートからの相対パスの完全一致で管理し、暗黙の除外は作らない。
     * 2026-08-30時点、apap-testkitの`InMemoryClock`/`InMemoryIdGenerator`はいずれも決定的な
     * フェイク実装であり実際にはこれらのAPIを呼ばない（許可リストへの追加不要）。
     * P9（apap-runtime埋込ファサード）で追加した[apap.runtime.SystemClock]/
     * [apap.runtime.UlidIdGenerator]は、`ApapEngineBuilder`の`clock`/`idGenerator`未指定時の
     * 既定実装（実Clock/実乱数）であり、ここが許可すべき対象そのもの。
     */
    private val allowlist =
        setOf(
            // 実Provider AdapterのSLA適合性（実測レイテンシ）を検証するハーネス。
            // 偽Clockを注入すると実時間の計測にならず検証の意味が失われるため、直接呼び出しを許可する。
            "modules/apap-testkit/src/main/kotlin/apap/testkit/contract/AdapterContractTest.kt",
            // apap-runtime埋込ファサードの既定Clock実装（未指定時のみ使用される）。
            "modules/apap-runtime/src/main/kotlin/apap/runtime/SystemClock.kt",
            // apap-runtime埋込ファサードの既定IdGenerator実装（未指定時のみ使用される）。
            "modules/apap-runtime/src/main/kotlin/apap/runtime/UlidIdGenerator.kt",
        )

    @Test
    fun `production code (src main) does not call the system clock or UUID directly`() {
        val repoRoot = findRepoRoot(File(".").canonicalFile)
        val existingRoots = scannedRoots.map { File(repoRoot, it) }.filter { it.exists() }
        val mainSourceFiles = existingRoots.flatMap { root -> mainSourceKotlinFiles(root) }

        assertTrue(
            mainSourceFiles.isNotEmpty(),
            "Clock/IdGenerator直接呼び出しスキャン対象が0件です（対象: $scannedRoots のsrc/main）。" +
                "この状態では違反を検出できません（ArchitectureScopeGuard.ktと同じ理由）。",
        )

        val violations =
            mainSourceFiles
                .filterNot { it.relativeTo(repoRoot).path in allowlist }
                .flatMap { file -> violationsInFile(file, repoRoot) }

        if (violations.isNotEmpty()) {
            fail<Unit>(
                "Clock/IdGeneratorの直接呼び出しを検出しました（CLAUDE.md実装規約: " +
                    "テストの決定性のためPort経由での注入が必須）:\n" +
                    violations.joinToString("\n"),
            )
        }
    }

    private fun mainSourceKotlinFiles(root: File): List<File> =
        root
            .walkTopDown()
            .onEnter { dir -> dir.name !in excludedDirNames }
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path.replace(File.separatorChar, '/') }
            .toList()

    private fun violationsInFile(
        file: File,
        repoRoot: File,
    ): List<String> {
        val withoutComments = stripComments(file.readText())
        return forbiddenPatterns
            .flatMap { pattern -> pattern.findAll(withoutComments).map { it.value } }
            .map { match -> "${file.relativeTo(repoRoot)}: calls $match directly" }
    }

    /** ブロックコメント（KDoc含む）と行コメントを、判定前に除去する（VendorNeutralityTestと同じ配慮）。 */
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
}
