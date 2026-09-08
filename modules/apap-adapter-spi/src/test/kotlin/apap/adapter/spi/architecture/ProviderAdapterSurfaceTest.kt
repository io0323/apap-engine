package apap.adapter.spi.architecture

import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.SpiSurface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * ADR-0042: **SPIのメソッドと本番の消費者の対応**（[SpiSurface.adapterMethodConsumers]）を機械検証する。
 *
 * ## 何を防ぐ検査か
 *
 * `capabilityConstraints`は2フェーズにわたり「Adapterが実装し、テストのフェイクも実装し、
 * しかし本番の誰も読まない」まま残った。Adapter作者から見れば意味のあるメソッドであり、
 * 消費者がいないことは実装側からは見えない——**シグナルの不在**であって、単体テストをいくら
 * 足しても検出できない（CLAUDE.md 不変条件9の失敗の形そのもの）。
 *
 * そこでメソッド集合をクローズドセットとして管理し、次の3つを検査する。
 *
 * 1. 表と[ProviderAdapter]の宣言が過不足なく一致すること（メソッドを足したら表の更新が要る）
 * 2. 消費者を宣言した項目には、本番ソースに実際の呼び出しがあること
 * 3. 「消費者なし」と宣言した項目には、**本当に呼び出しが無い**こと
 *    （配線したのに表が古いまま、を防ぐ。表は両方向に嘘をつけない）
 */
class ProviderAdapterSurfaceTest {
    @Test
    fun `the consumer table covers exactly the methods ProviderAdapter declares`() {
        assertEquals(
            declaredMethodNames(),
            SpiSurface.adapterMethodConsumers.keys
                .sorted()
                .toSet(),
            "SpiSurface.adapterMethodConsumersとProviderAdapterの宣言が一致しません。" +
                "SPIへメソッドを追加・削除したら、その消費者（または消費者が無い理由）を必ず表へ書くこと。",
        )
    }

    @Test
    fun `a method that claims a consumer is actually called from production code`() {
        val mainSources = productionSources()
        assertTrue(mainSources.size > MIN_SCANNED_FILES, "本番ソースの走査に失敗しています: ${mainSources.size}件")

        val claimed = SpiSurface.adapterMethodConsumers.filterValues { !it.startsWith(SpiSurface.NO_CONSUMER_PREFIX) }
        val missing = claimed.keys.filterNot { method -> isCalledIn(mainSources, method) }

        assertTrue(
            missing.isEmpty(),
            "消費者を宣言しているのに本番の呼び出しが見つかりません: $missing。" +
                "配線を外したなら SpiSurface.adapterMethodConsumers を" +
                "「${SpiSurface.NO_CONSUMER_PREFIX} 理由」へ書き換えること" +
                "（呼び出しは `adapter.<method>(` の形を検出する）。",
        )
    }

    @Test
    fun `a method declared as having no consumer really has none`() {
        val mainSources = productionSources()
        val declaredUnused =
            SpiSurface.adapterMethodConsumers.filterValues { it.startsWith(SpiSurface.NO_CONSUMER_PREFIX) }

        val nowCalled = declaredUnused.keys.filter { method -> isCalledIn(mainSources, method) }
        assertTrue(
            nowCalled.isEmpty(),
            "「消費者なし」と宣言されているのに本番から呼ばれています: $nowCalled。" +
                "配線したなら SpiSurface.adapterMethodConsumers に呼び出し元を書くこと。",
        )

        val withoutReason =
            declaredUnused.filterValues { it.removePrefix(SpiSurface.NO_CONSUMER_PREFIX).isBlank() }
        assertTrue(
            withoutReason.isEmpty(),
            "消費者が無いことを宣言するなら理由を書くこと（空のまま増やさないため）: ${withoutReason.keys}",
        )
    }

    /**
     * [ProviderAdapter]が宣言するメソッド名。ネストした`AdapterStream`は別インターフェースであり、
     * `next`/`cancel`はAdapter実装の面ではないため含まれない。
     */
    private fun declaredMethodNames(): Set<String> =
        ProviderAdapter::class.java.declaredMethods
            .asSequence()
            .filterNot { it.isSynthetic || it.isBridge }
            .map { it.name }
            .filterNot { it.contains('$') }
            .toSortedSet()

    /**
     * 本番ソース（`modules/<module>/src/main` と `gateway/<module>/src/main`）。
     *
     * `apap-adapter-spi`自身（宣言そのもの）と`apap-testkit`（契約テストの置き場であって本番経路ではない）
     * は除く。**テストが呼んでいることを消費者と数えない**のがこの検査の要点で、
     * `capabilityConstraints`はまさに「フェイクだけが実装していた」状態だった。
     */
    private fun productionSources(): List<String> {
        val root = repoRoot()
        val roots = listOf(File(root, "modules"), File(root, "gateway")).filter { it.isDirectory }
        return roots
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .filterNot { it.name == "apap-adapter-spi" || it.name == "apap-testkit" }
            .map { File(it, "src/main") }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .map { withoutCommentsAndLiterals(it.readText()) }
    }

    /**
     * コメントと文字列リテラルを落とす。
     *
     * これが無いと、KDocや例外メッセージがメソッド名に言及しているだけで「消費者あり」と読めてしまう
     * （実際、`EndpointCatalog`が`discoverModels()`を**未提供APIとして説明する文字列**を持っており、
     * 素朴な検索はそれを呼び出しと誤認した）。検査自身が偽陽性を出す状態では、
     * 「消費者なし」の宣言を守れない。
     */
    private fun withoutCommentsAndLiterals(source: String): String =
        source
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
            .replace(Regex("//[^\\n]*"), " ")
            .replace(Regex("\"\"\"[\\s\\S]*?\"\"\""), " ")
            .replace(Regex("\"(\\\\.|[^\"\\\\\\n])*\""), " ")

    /** `adapter.<method>(`の形の呼び出しを探す（本番の呼び出しは解決済みAdapter経由で必ずこの形になる）。 */
    private fun isCalledIn(
        sources: List<String>,
        method: String,
    ): Boolean {
        val call = Regex("""[Aa]dapter\.$method\s*\(""")
        return sources.any { call.containsMatchIn(it) }
    }

    private fun repoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts not found; cannot locate the repository root")
    }

    private companion object {
        /** 走査対象が実質0件のまま緑になるのを防ぐ下限（CLAUDE.md「Konsistの空スコープ」と同じ趣旨）。 */
        const val MIN_SCANNED_FILES = 50
    }
}
