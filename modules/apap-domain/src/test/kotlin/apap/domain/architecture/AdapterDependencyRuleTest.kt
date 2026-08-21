package apap.domain.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

/**
 * 01_CLAUDE.md 不変条件2 / docs/design/08_パッケージ図.md:
 * adapters配下の各Pluginモジュールの**mainソースセット**（配布されるPlugin本体）は apap-adapter-spi
 * のみに依存し、コア（domain/execution/routing/...）へは依存しない。ビルド設定（project依存）だけでなく、
 * 実装コードのimportも機械検証する。
 *
 * ADR-0015: testソースセットはapap-testkit（Contract Test・In-Memory Port実装。CLAUDE.md設計書用語対応表で
 * 「Adapter Contract Testの置き場」と明記）への依存を別途許可する。配布物（mainの成果物）には
 * 含まれないため、01_CLAUDE.md不変条件2が保護する依存境界を損なわない。
 *
 * このテストは apap-domain モジュールに置かれているが、Konsistはリポジトリルートを
 * 自動検出するため、他モジュール（adapters/配下）のソースも横断的に検査できる。
 */
class AdapterDependencyRuleTest {
    @Test
    fun `adapters main source does not import apap core packages other than adapter-spi`() {
        val scope = Konsist.scopeFromDirectory("adapters")
        assertScopeNotEmpty(scope, "adapters")
        val mainScope = scope.slice { it.resideInSourceSet("main") }
        assertScopeNotEmpty(mainScope, "adapters (main sourceSet)")

        mainScope
            .imports
            .assertFalse { import ->
                import.name.startsWith("apap.") && !import.name.startsWith("apap.adapter.spi")
            }
    }

    @Test
    fun `adapters test source does not import apap core packages other than adapter-spi or testkit`() {
        val scope = Konsist.scopeFromDirectory("adapters")
        val testScope = scope.slice { it.resideInSourceSet("test") }
        assertScopeNotEmpty(testScope, "adapters (test sourceSet)")

        testScope
            .imports
            .assertFalse { import ->
                import.name.startsWith("apap.") &&
                    !import.name.startsWith("apap.adapter.spi") &&
                    !import.name.startsWith("apap.testkit") &&
                    !import.name.startsWith("apap.domain")
            }
    }
}
