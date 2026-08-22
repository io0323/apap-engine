package apap.domain.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

/**
 * 01_CLAUDE.md 不変条件2: apap-domain は他モジュールへ依存しない（依存はすべて内向き）。
 * docs/design/08_パッケージ図.md: domainはいかなる外部にも依存しない。
 *
 * apap.domain.. の実装が、外側のレイヤー（infrastructure/execution/routing）や
 * Ktor・DIフレームワークをimportしていないことを機械検証する。
 */
class DomainDependencyRuleTest {
    private val forbiddenImportPrefixes =
        listOf(
            // 外側レイヤー（Clean Architectureの依存規則違反）
            "apap.infrastructure",
            "apap.execution",
            "apap.routing",
            "apap.provider",
            "apap.adapter.spi",
            "apap.testkit",
            "apap.plugin",
            "apap.api",
            "apap.application",
            "apap.prompt",
            "apap.context",
            // Presentation/Transportフレームワーク
            "io.ktor",
            // DIコンテナ・アプリケーションフレームワーク（01_CLAUDE.md 不変条件6）
            "org.springframework",
            "org.koin",
            "com.google.inject",
            "dagger",
            "jakarta.inject",
            "javax.inject",
        )

    @Test
    fun `apap-domain does not import outer layers, Ktor or DI frameworks`() {
        val scope = Konsist.scopeFromDirectory("modules/apap-domain")
        assertScopeNotEmpty(scope, "modules/apap-domain")

        scope
            .imports
            .assertFalse { import ->
                forbiddenImportPrefixes.any { forbidden ->
                    import.name == forbidden || import.name.startsWith("$forbidden.")
                }
            }
    }
}
