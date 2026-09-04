package apap.adapter.anthropic

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 記録データの健全性を機械検証する。狙いは2つ。
 *
 * 1. **出所の明示**: 各記録が「実APIの記録」か「仕様からの手書き」かを宣言していること。
 *    宣言が無いと、手書きの想定を実APIの挙動だと誤読したままSPIの結論を出してしまう。
 * 2. **機微情報の非混入**: 実APIから記録し直したときに、鍵やアカウント識別子が
 *    そのままコミットされるのを防ぐ（制約「記録データに機微情報を含めない」）。
 */
class RecordingProvenanceTest {
    private val mapper = ObjectMapper()

    @Test
    fun `every recording declares where it came from`() {
        val files = recordingFiles()
        assertTrue(files.isNotEmpty(), "記録データが1件も見つかりません（走査の破綻）")
        val undeclared =
            files.filter { file ->
                mapper
                    .readTree(file)
                    .path("source")
                    .asText("")
                    .isBlank()
            }
        assertTrue(
            undeclared.isEmpty(),
            "出所（source）が宣言されていない記録があります: ${undeclared.map { it.name }}。" +
                "実APIの記録か手書きかを書かないと、想定を実挙動と取り違えます。",
        )
    }

    @Test
    fun `no recording contains anything that looks like a credential or personal data`() {
        val leaks =
            recordingFiles().flatMap { file ->
                val text = file.readText()
                SENSITIVE_PATTERNS
                    .filter { (_, pattern) -> pattern.containsMatchIn(text) }
                    .map { (label, _) -> "${file.name}: $label" }
            }
        assertTrue(
            leaks.isEmpty(),
            "記録データに機微情報らしき文字列が含まれています: $leaks。" +
                "実APIから記録し直した場合は、鍵・組織ID・メールアドレスをマスクしてからコミットすること。",
        )
    }

    @Test
    fun `recordings are valid json and carry either a reply or events`() {
        val broken =
            recordingFiles().filter { file ->
                val node = runCatching { mapper.readTree(file) }.getOrNull()
                node == null || (node.path("reply").isMissingNode && node.path("events").isEmpty)
            }
        assertTrue(broken.isEmpty(), "reply も events も持たない、または壊れた記録があります: ${broken.map { it.name }}")
    }

    @Test
    fun `the fixtures are declared as hand-authored while no live recording exists`() {
        // 実記録へ差し替えたらこのテストを更新すること。宣言と実態がずれたまま
        // 「実APIで検証済み」と読まれるのを防ぐための、意図的な足かせ。
        val handAuthored =
            recordingFiles().all { file ->
                mapper
                    .readTree(file)
                    .path("source")
                    .asText("")
                    .contains("hand-authored")
            }
        assertTrue(
            handAuthored,
            "実APIの記録が混ざっています。docs/adapter-spi-findings.md の「実APIとの接触は未実施」" +
                "という記述も併せて更新してください。",
        )
        assertFalse(
            recordingFiles().isEmpty(),
            "記録が空です",
        )
    }

    private fun recordingFiles(): List<File> {
        val dir = File(javaClass.getResource("/recordings")!!.toURI())
        return dir.listFiles { f: File -> f.extension == "json" }?.sortedBy { it.name }.orEmpty()
    }

    private companion object {
        val SENSITIVE_PATTERNS =
            listOf(
                "APIキーらしき文字列" to Regex("""sk-[A-Za-z0-9_-]{8,}"""),
                "Bearerトークン" to Regex("""(?i)bearer\s+[A-Za-z0-9._-]{12,}"""),
                "x-api-keyヘッダ値" to Regex("""(?i)"x-api-key"\s*:\s*"(?!redacted)[^"]{8,}""""),
                "メールアドレス" to Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""),
            )
    }
}
