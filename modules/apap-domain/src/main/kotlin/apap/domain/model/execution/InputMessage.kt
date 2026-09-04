package apap.domain.model.execution

import apap.domain.model.conversation.TurnRole
import apap.domain.model.vo.ContentPart

/**
 * 13_API設計.md 13.2 のリクエスト `messages[]`（role付きの1発話）。
 *
 * ## なぜ必要か（P11-F4 / ADR-0031）
 *
 * 13.2は入力を `messages[]{ role, content[] }` と定義しているのに対し、
 * 03_基本設計.md 3.3.1 の `CanonicalRequest.input` は `ContentPart[]` で **roleを持たない**。
 * 実装は型定義に忠実に従った結果、次の3箇所でroleが失われていた。
 *
 * 1. Gatewayの `ChatRequestDto.toApapRequest` が `messages.flatMap { it.content }` で平坦化
 * 2. `ExecutionEngine.buildContextualPrompt` が `assembled.turns.flatMap { it.contentParts }` で
 *    履歴の [TurnRole] を捨てる
 * 3. `AdapterRequest.input` が `ContentPart[]` で、SPIとしてroleを表現できない
 *
 * つまりマルチターンの会話が、発話者の区別が無い平坦な連結としてProviderへ渡っていた。
 * System Promptに至っては `buildContextualPrompt` が `systemPrompt = emptyList()` を
 * ハードコードしており、そもそも供給経路が無かった。
 *
 * ## [TurnRole] を再利用する理由
 *
 * 04_ドメイン設計.md 4.3.4 の `Turn.role` と同じ概念であり、新しいrole概念を作ると
 * 「履歴のrole」と「入力のrole」の2系統ができて変換が必要になる。9章・14章の用語とも揃える。
 */
data class InputMessage(
    val role: TurnRole,
    val content: List<ContentPart>,
) {
    companion object {
        /**
         * role情報を持たない旧経路（`input: List<ContentPart>`）からの既定変換。
         * 単一のUSER発話とみなす——role未指定の入力をSYSTEMやASSISTANTと解釈するのは
         * 危険側の推測になるため、最も権限の低いUSERへ倒す。
         */
        fun userOnly(content: List<ContentPart>): List<InputMessage> =
            if (content.isEmpty()) emptyList() else listOf(InputMessage(TurnRole.USER, content))
    }
}
