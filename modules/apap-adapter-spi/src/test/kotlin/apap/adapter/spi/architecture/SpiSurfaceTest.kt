package apap.adapter.spi.architecture

import apap.adapter.spi.SpiSurface
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR-0016 決定2: [SpiSurface.exposedDomainTypes]の一覧と、
 * `DomainAliases.kt`が実際に宣言しているtypealiasが完全一致することを機械検証する。
 * SPI公開面（再エクスポートされるドメイン型の集合）への変更が、レビュー対象のdiffに現れず
 * 見逃されることを防ぐ（[SpiSurface]を更新し忘れるとこのテストが落ちる）。
 */
class SpiSurfaceTest {
    @Test
    fun `SpiSurface exposedDomainTypes matches the typealias declarations in DomainAliases`() {
        val scope = Konsist.scopeFromDirectory("modules/apap-adapter-spi")
        assertScopeNotEmpty(scope, "modules/apap-adapter-spi")

        val declaredTypeAliases =
            scope.typeAliases
                .associate { it.name to it.type.name }

        assertTrue(
            declaredTypeAliases.isNotEmpty(),
            "DomainAliases.ktからtypealias宣言を1件も検出できませんでした。Konsistのtypealias走査に" +
                "失敗している可能性があり、この状態ではSPI公開面の不一致を検出できません。",
        )

        assertEquals(
            SpiSurface.exposedDomainTypes,
            declaredTypeAliases,
            "SpiSurface.exposedDomainTypesとDomainAliases.ktの実際のtypealias宣言が一致しません。" +
                "typealiasを追加・削除・変更した場合は、SpiSurface.exposedDomainTypesも必ず同時に" +
                "更新してください（ADR-0016）。",
        )
    }

    /**
     * Konsistのスコープ取得が対象0件で返ると、このテストは違反を検出せず沈黙して成功してしまう
     * （CLAUDE.md「Konsistの空スコープ」参照）。apap-adapter-spiにはapap-domainのような
     * 共有ガードヘルパーへの依存経路がない（testソースセット同士は依存できない）ため、
     * 同じ趣旨の最小限のガードをここに複製する。
     */
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
