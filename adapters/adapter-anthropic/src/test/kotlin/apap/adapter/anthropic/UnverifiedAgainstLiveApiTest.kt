package apap.adapter.anthropic

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 「未検証である」という申告と、実際の状態・文書が食い違わないことを機械検証する。
 *
 * 申告だけならいつでも書き換えられてしまう。**記録データの出所**と**findings文書の記述**の
 * 3点が揃って初めて意味を持つので、3点セットで縛る。
 */
class UnverifiedAgainstLiveApiTest {
    @Test
    fun `while unverified, the fixtures must still be hand-authored`() {
        if (UnverifiedAgainstLiveApi.LIVE_VERIFIED) return
        val dir = File(javaClass.getResource("/recordings")!!.toURI())
        val live =
            dir
                .listFiles { f: File -> f.extension == "json" }
                .orEmpty()
                .filter { !it.readText().contains("hand-authored") }
        assertTrue(
            live.isEmpty(),
            "LIVE_VERIFIED=false のまま実記録が混ざっています: ${live.map { it.name }}。" +
                "実測が済んだのなら LIVE_VERIFIED と findings 文書も併せて更新すること。",
        )
    }

    @Test
    fun `while unverified, the findings document must say so`() {
        if (UnverifiedAgainstLiveApi.LIVE_VERIFIED) return
        val findings = File(repoRoot(), "docs/adapter-spi-findings.md")
        assertTrue(findings.exists(), "findings文書が見つかりません: ${findings.path}")
        val text = findings.readText()
        assertTrue(
            text.contains("実APIへの接続は行っていない") || text.contains("実APIとの接触は未実施"),
            "LIVE_VERIFIED=false なのに findings 文書が未検証である旨を述べていません。" +
                "コードと文書が食い違うと、読んだ人は文書を信じます。",
        )
        assertTrue(
            text.contains("[要実測]"),
            "[要実測]の印が findings 文書から消えています。" +
                "この印が「一通り検証した」という記憶への置き換わりを防ぐ唯一の手段です。",
        )
    }

    private fun repoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("リポジトリルートを特定できません")
    }
}
