package apap.domain.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

/**
 * 01_CLAUDE.md 不変条件2 / docs/design/08_パッケージ図.md:
 * adapters配下の各Pluginモジュールは apap-adapter-spi のみに依存し、コア（domain/execution/routing/...）へは
 * 依存しない。ビルド設定（project依存）だけでなく、実装コードのimportも機械検証する。
 *
 * このテストは apap-domain モジュールに置かれているが、Konsistはリポジトリルートを
 * 自動検出するため、他モジュール（adapters/配下）のソースも横断的に検査できる。
 */
class AdapterDependencyRuleTest {
    @Test
    fun `adapters source does not import apap core packages other than adapter-spi`() {
        Konsist
            .scopeFromDirectory("adapters")
            .imports
            .assertFalse { import ->
                import.name.startsWith("apap.") && !import.name.startsWith("apap.adapter.spi")
            }
    }
}
