package apap.domain.architecture

import com.lemonappdev.konsist.api.container.KoScope
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Konsistのスコープ取得が対象0件で返ると、アーキテクチャテストは違反を検出せず
 * 沈黙して成功する（P1で scopeFromModule 使用時に発覚した失敗モード）。
 * 全アーキテクチャテストは検証の前に必ずこのガードを呼び、スコープ取得失敗を
 * 規約違反と同様に「テストが落ちる」状態にすること。
 */
fun assertScopeNotEmpty(
    scope: KoScope,
    description: String,
) {
    assertTrue(
        scope.files.isNotEmpty(),
        "Konsistスコープ「$description」の対象ファイルが0件です。" +
            "スコープ解決に失敗している可能性があり、この状態では規約違反を検出できません。",
    )
}
