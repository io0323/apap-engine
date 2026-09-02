package apap.hostcompat

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * ADR-0029: このモジュールの**mainコンパイルクラスパス**に、埋込ホストからは見えないはずの
 * APAP内部モジュールが混入していないことを機械検証する。
 *
 * 混入すると「ホストでもコンパイルできる」という検証結果が嘘になる——P9で実際に、
 * ホストから見えない`apap.execution.ExecutionFailedException`をimportするコード例を
 * ドキュメントに載せてしまった（当時ドキュメントは検査対象外だった）。
 *
 * Gradleの解決結果そのものを見るため、`build.gradle.kts`の宣言を読むのではなく
 * **実際のコンパイルクラスパス（jarのファイル名）**を突き合わせる。宣言だけを見ると
 * 推移的に入ってくるものを見落とす。
 */
class HostCompileClasspathTest {
    @Test
    fun `the host compile classpath contains no internal APAP modules`() {
        val entries = compileClasspathEntries()
        assertTrue(
            entries.isNotEmpty(),
            "compile classpath could not be read (${CLASSPATH_FILE}). " +
                "An empty classpath would make this test pass vacuously.",
        )
        // 検証対象が本当に載っていることの確認: apap-runtimeが見えないなら読み取りが壊れている。
        assertTrue(
            entries.any { it.contains("apap-runtime") },
            "apap-runtime is absent from the classpath, so the reader is broken rather than the dependency set: $entries",
        )

        val leaked = FORBIDDEN_MODULES.filter { forbidden -> entries.any { it.matchesModule(forbidden) } }
        if (leaked.isNotEmpty()) {
            fail<Unit>(
                "These modules are on the host compile classpath but are NOT visible to an embedding host " +
                    "(apap-runtime depends on them with `implementation` scope). Code examples compiled here " +
                    "would therefore not compile in prompt-engine (ADR-0029):\n" +
                    leaked.joinToString("\n") { "  $it" } +
                    "\nclasspath: ${entries.sorted()}",
            )
        }
    }

    /**
     * ファイル名の部分一致だと`apap-infrastructure`が`apap-infrastructure-jdbc`にも当たるなど
     * 取りこぼし/過検出が出るため、`<module>.jar`または`<module>/build/...`の形で境界を見る。
     */
    private fun String.matchesModule(module: String): Boolean =
        contains("/$module.jar") ||
            contains("/$module/build/") ||
            endsWith("/$module")

    private fun compileClasspathEntries(): List<String> {
        val file = File(CLASSPATH_FILE)
        if (!file.exists()) return emptyList()
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private companion object {
        /**
         * `build.gradle.kts`の`dumpCompileClasspath`タスクが書き出す。テストからGradleの
         * 依存解決APIへは触れないため（Tooling APIをテスト依存に入れたくない）、
         * ビルド時に書き出したものを読む形にしている。
         */
        const val CLASSPATH_FILE = "build/host-compat/compile-classpath.txt"

        /**
         * `apap-runtime`が`implementation`スコープで依存しており、埋込ホストからは
         * 到達できないモジュール群。ホストの`build.gradle.kts`は
         * `apap-runtime`と`apap-api`しか宣言しない前提。
         */
        val FORBIDDEN_MODULES =
            listOf(
                "apap-execution",
                "apap-routing",
                "apap-context",
                "apap-prompt",
                "apap-cache",
                "apap-cost",
                "apap-plugin",
                "apap-observability",
                "apap-application",
                "apap-infrastructure",
                "apap-infrastructure-jdbc",
                "apap-infrastructure-distributed",
                "apap-testkit",
            )
    }
}
