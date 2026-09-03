package apap.domain.architecture

import apap.domain.architecture.ModuleScanCoverage.ScanExclusion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [ModuleScanCoverage]自身の単体テスト。
 *
 * 実リポジトリでは現在ひとつも除外を使っていないため、除外まわりのコードパスは
 * 本番の検査からは一度も実行されない。**使われていない逃げ道は壊れていても気づけない**
 * （本プロジェクトが繰り返し踏んできた失敗の形）ので、ここで人工の`settings.gradle.kts`を
 * 使って全分岐を実行する。
 */
class ModuleScanCoverageTest {
    @TempDir
    lateinit var tempDir: File

    private fun repoWith(vararg modules: String): File {
        val settings = File(tempDir, "settings.gradle.kts")
        // trimIndentは補間後の全行から共通インデントを算出するため、複数行を埋め込むと
        // インデントが崩れる。ここは検査対象そのものなので素朴に連結する。
        settings.writeText(
            "rootProject.name = \"fake\"\n\n" +
                "includeBuild(\"build-logic\")\n\n" +
                "include(\n" +
                modules.joinToString("") { "    \"$it\",\n" } +
                ")\n",
        )
        return tempDir
    }

    @Test
    fun `parses module paths from settings and ignores includeBuild`() {
        val root = repoWith("modules:a", "gateway:b")
        assertEquals(listOf("modules/a", "gateway/b"), ModuleScanCoverage.includedModulePaths(root))
    }

    @Test
    fun `reads the real settings file of this repository`() {
        val real = ModuleScanCoverage.findRepoRoot(File(".").canonicalFile)
        val modules = ModuleScanCoverage.includedModulePaths(real)
        // パーサが実ファイルで機能していることの確認（人工データだけで緑になる状態を作らない）。
        assertTrue(modules.contains("modules/apap-domain"), "実settings.gradle.ktsの解析結果: $modules")
        assertTrue(modules.contains("integration/host-compat"), "実settings.gradle.ktsの解析結果: $modules")
        assertTrue(modules.none { it.contains("build-logic") }, "includeBuildはモジュールに含めない: $modules")
    }

    @Test
    fun `passes when every module is under a scanned root`() {
        val root = repoWith("modules:a", "gateway:b")
        ModuleScanCoverage.assertScanCoversAllModules("t", root, listOf("modules", "gateway"))
    }

    @Test
    fun `fails when a module is outside every scanned root`() {
        val root = repoWith("modules:a", "integration:c")
        val error =
            assertThrows(AssertionError::class.java) {
                ModuleScanCoverage.assertScanCoversAllModules("t", root, listOf("modules"))
            }
        assertTrue(error.message!!.contains("integration/c"), error.message)
    }

    @Test
    fun `passes when the uncovered module is excluded with a reason`() {
        val root = repoWith("modules:a", "integration:c")
        ModuleScanCoverage.assertScanCoversAllModules(
            "t",
            root,
            listOf("modules"),
            listOf(ScanExclusion("integration/c", "ホスト依存だけでコンパイルすること自体が検査のため対象外")),
        )
    }

    @Test
    fun `fails when an exclusion has a blank reason`() {
        val root = repoWith("modules:a", "integration:c")
        val error =
            assertThrows(AssertionError::class.java) {
                ModuleScanCoverage.assertScanCoversAllModules(
                    "t",
                    root,
                    listOf("modules"),
                    listOf(ScanExclusion("integration/c", "   ")),
                )
            }
        assertTrue(error.message!!.contains("理由がありません"), error.message)
    }

    @Test
    fun `fails when an exclusion points at a module that no longer exists`() {
        val root = repoWith("modules:a")
        val error =
            assertThrows(AssertionError::class.java) {
                ModuleScanCoverage.assertScanCoversAllModules(
                    "t",
                    root,
                    listOf("modules"),
                    listOf(ScanExclusion("modules/removed", "かつて存在したモジュール")),
                )
            }
        assertTrue(error.message!!.contains("モジュール一覧に存在しません"), error.message)
    }

    @Test
    fun `fails when the module list cannot be read`() {
        File(tempDir, "settings.gradle.kts").writeText("rootProject.name = \"fake\"\n")
        val error =
            assertThrows(AssertionError::class.java) {
                ModuleScanCoverage.assertScanCoversAllModules("t", tempDir, listOf("modules"))
            }
        assertTrue(error.message!!.contains("1件も読み取れませんでした"), error.message)
    }

    @Test
    fun `does not treat a prefix collision as coverage`() {
        // "modules-extra" は "modules" で始まるが別ルート。startsWith("modules")だけで
        // 判定すると誤って覆われているとみなす。
        val root = repoWith("modules-extra:a")
        val error =
            assertThrows(AssertionError::class.java) {
                ModuleScanCoverage.assertScanCoversAllModules("t", root, listOf("modules"))
            }
        assertTrue(error.message!!.contains("modules-extra/a"), error.message)
    }
}
