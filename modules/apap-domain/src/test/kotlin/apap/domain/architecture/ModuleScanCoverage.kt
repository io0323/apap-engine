package apap.domain.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import java.io.File

/**
 * リポジトリ全体を走査する検査が、**現在の全モジュールを実際に覆っているか**を機械検証する。
 *
 * ## 解決する失敗モード
 *
 * `ArchitectureScopeGuard.assertScopeNotEmpty` は「対象0件」を検出するが、
 * 「対象は0件ではないが、一部のモジュールがまるごと対象外」は検出できない。
 * 実際に `TestMethodReturnTypeTest` の `scannedRoots` が `modules`/`gateway`/`adapters` の
 * ままだったため、後から追加した `integration/host-compat` が規約検査の対象外になっていた。
 *
 * この漏れは**不変条件9（違反を注入して落ちることを確認する）では捕まらない**。
 * 注入先が対象モジュール内であれば正しく落ちるためで、注入者は「検査は機能している」と
 * 結論してしまう。検査の**対象範囲そのもの**を、モジュール一覧という独立した情報源
 * （`settings.gradle.kts`）と突き合わせる必要がある。
 *
 * ## 使い方
 *
 * リポジトリ全体を走査する検査は、検証本体の前に [assertScanCoversAllModules] を呼ぶ。
 * 走査ルートは検査が実際に使っている変数をそのまま渡すこと（値を書き写すと、
 * 検査側だけ変更されたときに登録側が古いまま通ってしまう）。
 *
 * 対象外にするモジュールは [ScanExclusion] で理由付きで明示する。理由が空の除外は失敗する
 * ——「なぜ対象外か」を書けない除外は、単なる漏れと区別できないため。
 *
 * ## 他モジュールから使う場合
 *
 * 現在リポジトリ全体を走査する検査は3件ともこのモジュール（apap-domain）のtestソースに
 * あるため、このファイルはtestソースに置いている。他モジュールのtestから同種の検査を
 * 追加する場合は、このヘルパを `apap-testkit` へ移して共有すること
 * （値を書き写した二重管理にしないこと）。`RepoWideScanRegistrationTest` が
 * 「`scannedRoots` を宣言するファイルは必ずこのヘルパを呼ぶ」ことを検証している。
 */
object ModuleScanCoverage {
    /**
     * 走査対象から意図的に外すモジュール。[reason] は必須で、空文字は許さない。
     *
     * @param modulePath `settings.gradle.kts` の `include` 表記をパス化したもの（例: `modules/apap-api`）
     * @param reason なぜこの検査の対象外でよいのか
     */
    data class ScanExclusion(
        val modulePath: String,
        val reason: String,
    )

    /**
     * [scannedRoots] が `settings.gradle.kts` の全モジュールを覆っていることを検証する。
     *
     * @param checkName 失敗メッセージに出す検査名
     * @param repoRoot リポジトリルート
     * @param scannedRoots 検査が実際に走査するルート（リポジトリルートからの相対パス）
     * @param exclusions 意図的に対象外とするモジュール（理由必須）
     */
    fun assertScanCoversAllModules(
        checkName: String,
        repoRoot: File,
        scannedRoots: List<String>,
        exclusions: List<ScanExclusion> = emptyList(),
    ) {
        val modules = includedModulePaths(repoRoot)

        // モジュール一覧の読み取り自体が壊れると、この検査もまた沈黙して成功する。
        assertTrue(
            modules.isNotEmpty(),
            "settings.gradle.ktsからモジュールを1件も読み取れませんでした（$checkName）。" +
                "includeブロックの記法が変わった可能性があります。この状態では対象範囲を検証できません。",
        )

        exclusions.forEach { exclusion ->
            assertTrue(
                exclusion.reason.isNotBlank(),
                "$checkName: 除外 ${exclusion.modulePath} に理由がありません。" +
                    "理由を書けない除外は「漏れ」と区別できないため許可しません。",
            )
            assertTrue(
                exclusion.modulePath in modules,
                "$checkName: 除外 ${exclusion.modulePath} は現在のモジュール一覧に存在しません。" +
                    "モジュールの削除・改名時に取り残された除外です。削除してください。",
            )
        }

        val excludedPaths = exclusions.map { it.modulePath }.toSet()
        val uncovered =
            modules
                .filterNot { module -> scannedRoots.any { root -> module == root || module.startsWith("$root/") } }
                .filterNot { it in excludedPaths }

        if (uncovered.isNotEmpty()) {
            fail<Unit>(
                "$checkName の走査対象から漏れているモジュールがあります:\n" +
                    uncovered.joinToString("\n") { "  - $it" } +
                    "\n現在の走査ルート: $scannedRoots\n" +
                    "対処: 走査ルートを追加して覆うか、ModuleScanCoverage.ScanExclusion で" +
                    "理由付きの除外を宣言してください。",
            )
        }
    }

    /**
     * `settings.gradle.kts` の `include(...)` に列挙されたモジュールを、
     * `modules:apap-api` -> `modules/apap-api` の形で返す。
     *
     * モジュール一覧の唯一の情報源はGradleの設定ファイルであり、検査側に一覧を写し取ると
     * 「写し取った側の更新漏れ」という同じ問題を再生産するため、必ずここから読む。
     */
    fun includedModulePaths(repoRoot: File): List<String> {
        val settings = File(repoRoot, "settings.gradle.kts")
        if (!settings.exists()) return emptyList()
        val text = settings.readText()
        val includeBlock = INCLUDE_BLOCK.find(text)?.groupValues?.get(1) ?: return emptyList()
        return QUOTED.findAll(includeBlock)
            .map { it.groupValues[1].replace(':', '/') }
            .filter { it.isNotBlank() }
            .toList()
    }

    /** ルートから上へ辿って `settings.gradle.kts` のあるディレクトリを探す。 */
    fun findRepoRoot(start: File): File {
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts が見つからず、リポジトリルートを特定できません（起点: $start）")
    }

    /**
     * `include(` から対応する `)` までを1グループとして取る。`includeBuild("build-logic")` は
     * `include` の直後が `(` ではないためこの正規表現には一致せず、モジュールとして数えられない。
     * 行頭の空白を許容する（インデントの有無で解析結果が変わると、失敗が「モジュール0件」という
     * 分かりにくい形で出るため）。
     */
    private val INCLUDE_BLOCK =
        Regex("""^[ \t]*include\(([^)]*)\)""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))

    /** ダブルクォートで囲まれたモジュール表記。行コメント中のものは含めない。 */
    private val QUOTED = Regex(""""([^"]+)"""")
}
