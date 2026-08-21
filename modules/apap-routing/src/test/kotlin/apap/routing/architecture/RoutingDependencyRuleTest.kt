package apap.routing.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CLAUDE.md 依存方向: apap-routingのmainソースセットは apap-domain にのみ依存する。
 * RoutingはAdapterを直接呼び出さないため apap-adapter-spi も不要（apap-provider経由で
 * 間接的にも依存させない）。
 */
class RoutingDependencyRuleTest {
    @Test
    fun `apap-routing main source does not import apap-provider, adapter-spi or other outer layers`() {
        val scope = Konsist.scopeFromDirectory("modules/apap-routing")
        assertScopeNotEmpty(scope, "modules/apap-routing")
        val mainScope = scope.slice { it.resideInSourceSet("main") }
        assertScopeNotEmpty(mainScope, "modules/apap-routing (main sourceSet)")

        mainScope
            .imports
            .assertFalse { import ->
                import.name.startsWith("apap.") &&
                    !import.name.startsWith("apap.domain") &&
                    !import.name.startsWith("apap.routing")
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
