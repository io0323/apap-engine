package apap.runtime.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * P9: apap-runtime（埋込ファサード）の埋込制約を機械検証する。
 * - CLAUDE.md不変条件6: apap-runtimeとその依存モジュールはDIコンテナ・アプリフレームワーク・
 *   ロギング実装（SLF4J API / OpenTelemetry APIまでは可）を持ち込まない。
 * - `ApapEngineBuilder`未指定時（既定構成、In-Memory実装）の依存グラフに
 *   `apap-infrastructure-jdbc` / `apap-infrastructure-distributed` が含まれないこと
 *   （両モジュールは埋込ホストが明示的に注入したい場合のみ使う想定）。
 *
 * このリポジトリにGradle Tooling APIで実際に解決したruntimeClasspathをJUnitテストから検査する
 * 前例が無い（`af14b4201133ceec8`調査で確認済み）ため、`VendorNeutralityTest`と同じテキスト
 * スキャンの手法で代替する: 各モジュールの`build.gradle.kts`の`api(...)`/`implementation(...)`
 * （テストスコープは対象外——「既定構成の依存グラフ」＝埋込先アプリの本番クラスパスの検証が
 * 目的のため）に現れる`project(":modules:...")`宣言を、`apap-runtime`を起点に再帰的にたどって
 * モジュール依存の閉包を求める。
 */
class EmbeddingConstraintTest {
    private val moduleProjectRegex = Regex(""":(?:modules|gateway|adapters):[A-Za-z0-9-]+""")
    private val rootModule = "modules:apap-runtime"

    /**
     * CLAUDE.md不変条件6が禁止する「DIコンテナ・アプリフレームワーク・ロギング実装」の
     * 代表的な外部ライブラリ座標の断片。SLF4J API（`org.slf4j:slf4j-api`）自体とOpenTelemetry
     * APIは不変条件6が明示的に許可しているため対象外。
     */
    private val forbiddenExternalCoordinateFragments =
        listOf(
            "org.springframework",
            "com.google.inject",
            "com.google.dagger",
            "io.micronaut",
            "io.quarkus",
            "ch.qos.logback",
            "org.apache.logging.log4j",
            "slf4j-simple",
            "slf4j-jdk14",
            "slf4j-log4j12",
        )

    @Test
    fun `default apap-runtime module dependency closure excludes jdbc and distributed infrastructure`() {
        val repoRoot = findRepoRoot(File(".").canonicalFile)
        val closure = transitiveModuleClosure(repoRoot, rootModule)

        assertTrue(
            closure.isNotEmpty(),
            "apap-runtimeのモジュール依存閉包が0件です。build.gradle.ktsパーサが壊れている可能性があります" +
                "（この状態では違反を検出できません）。",
        )

        val forbidden = setOf("modules:apap-infrastructure-jdbc", "modules:apap-infrastructure-distributed")
        val leaked = closure intersect forbidden
        assertTrue(
            leaked.isEmpty(),
            "apap-runtimeの既定依存グラフに埋込制約で禁止されたモジュールが含まれています: $leaked" +
                "（ApapEngineBuilderは未指定時In-Memory実装を使う設計のため、jdbc/distributedへの" +
                "project依存はapap-runtime自身やその推移的依存に一切現れてはならない）",
        )
    }

    @Test
    fun `apap-runtime module dependency closure brings in no DI container, app framework, or logging implementation`() {
        val repoRoot = findRepoRoot(File(".").canonicalFile)
        val closure = transitiveModuleClosure(repoRoot, rootModule) + rootModule

        val violations =
            closure.flatMap { modulePath ->
                val buildFile = buildFileFor(repoRoot, modulePath)
                if (!buildFile.exists()) return@flatMap emptyList()
                val mainScopeText = mainScopeLines(buildFile).joinToString("\n")
                forbiddenExternalCoordinateFragments
                    .filter { fragment -> fragment in mainScopeText }
                    .map { fragment ->
                        "$modulePath/build.gradle.kts: references forbidden coordinate fragment \"$fragment\""
                    }
            }

        assertTrue(
            violations.isEmpty(),
            "CLAUDE.md不変条件6違反（apap-runtimeとその依存にDI/アプリフレームワーク/ロギング実装は" +
                "持ち込まない。SLF4J APIとOpenTelemetry APIまでは可）:\n${violations.joinToString("\n")}",
        )
    }

    private fun transitiveModuleClosure(
        repoRoot: File,
        root: String,
    ): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val buildFile = buildFileFor(repoRoot, current)
            if (!buildFile.exists()) continue
            val deps =
                mainScopeLines(buildFile)
                    .flatMap { line -> moduleProjectRegex.findAll(line).map { it.value.removePrefix(":") } }
                    .toSet()
            for (dep in deps) {
                if (dep != current && visited.add(dep)) {
                    queue.add(dep)
                }
            }
        }
        return visited
    }

    /** テストスコープ（`testImplementation`等）を除いた、`api(...)`/`implementation(...)`行のみ。 */
    private fun mainScopeLines(buildFile: File): List<String> =
        buildFile
            .readLines()
            .filter { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("api(") || trimmed.startsWith("implementation(")
            }

    private fun buildFileFor(
        repoRoot: File,
        modulePath: String,
    ): File = File(repoRoot, "${modulePath.replace(":", "/")}/build.gradle.kts")

    private fun findRepoRoot(start: File): File {
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts が見つからず、リポジトリルートを特定できません（起点: $start）")
    }
}
