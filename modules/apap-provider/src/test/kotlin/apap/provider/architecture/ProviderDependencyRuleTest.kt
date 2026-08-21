package apap.provider.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CLAUDE.md 依存方向: apap-providerのmainソースセットは apap-domain と apap-adapter-spi
 * （VALIDATING処理でProviderAdapter/PluginManifestを直接扱うため）にのみ依存し、apap-routing等の
 * 他モジュールへは依存しない。
 */
class ProviderDependencyRuleTest {
    @Test
    fun `apap-provider main source does not import apap-routing or other outer layers`() {
        val scope = Konsist.scopeFromDirectory("modules/apap-provider")
        assertScopeNotEmpty(scope, "modules/apap-provider")
        val mainScope = scope.slice { it.resideInSourceSet("main") }
        assertScopeNotEmpty(mainScope, "modules/apap-provider (main sourceSet)")

        mainScope
            .imports
            .assertFalse { import ->
                import.name.startsWith("apap.") &&
                    !import.name.startsWith("apap.domain") &&
                    !import.name.startsWith("apap.adapter.spi") &&
                    !import.name.startsWith("apap.provider")
            }
    }

    private fun assertScopeNotEmpty(
        scope: KoScope,
        description: String,
    ) {
        assertTrue(
            scope.files.isNotEmpty(),
            "Konsistスコープ「$description」の対象ファイルが0件です。" +
                "スコープ解決に失敗している可能性があり、この状態では規約違反を検出できません。",
        )
    }
}
