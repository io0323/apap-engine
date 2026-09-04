package apap.domain.model.execution

import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.TokenCount

/**
 * 03_基本設計.md 3.3.6 `PromptEngine.process`の戻り値、および`ContextManager.refit`の戻り値。
 * Prompt Pipeline（Validation→Optimization→Rendering）適用後の入力表現。PromptEngine（apap-prompt）と
 * ContextManager（apap-context）の双方が入出力として共有する型のため、両モジュール間に新規の依存を
 * 発生させないようapap-domainに置く（CanonicalRequest等と同じ判断）。
 */
data class ProcessedPrompt(
    val input: List<ContentPart>,
    val estimatedTokens: TokenCount = TokenCount.ZERO,
    /**
     * role付きの発話列（ADR-0031 / P11-F4）。[input]はこれを平坦化したものと同じ内容を持ち、
     * トークン計上・キャッシュキーなど**roleを見ない用途**はそのまま[input]を使ってよい。
     * Providerへ渡す際は[messages]を使うこと——[input]だけではSystem Promptと
     * ユーザ発話、過去のassistant応答が区別できない。
     */
    val messages: List<InputMessage> = InputMessage.userOnly(input),
)
