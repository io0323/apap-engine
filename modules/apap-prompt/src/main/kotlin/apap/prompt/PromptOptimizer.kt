package apap.prompt

import apap.domain.model.vo.ContentPart

/** 16_拡張ポイント.md 16.7「Optimization」。CLAUDE.md不変条件7に従いすべて設定可能。 */
data class PromptOptimizationConfig(
    val collapseWhitespace: Boolean = true,
    /** [PromptDraft.templateVariables]に無い変数の既定値（テナント語彙等）。呼出側指定が優先される。 */
    val staticVariables: Map<String, String> = emptyMap(),
)

/**
 * 16_拡張ポイント.md 16.7「Optimization」: トークン圧縮（冗長な空白の圧縮）+ テンプレート変数解決
 * （`{{name}}`形式のプレースホルダを[PromptDraft.templateVariables]（[PromptOptimizationConfig.staticVariables]で
 * 補完）で置換）。未解決の変数はそのまま残す（必須変数の厳格な検証は[TemplateRenderEngine]
 * （Rendering段、[PromptTemplate]経由の描画専用）の責務であり、ここでは行わない）。
 */
class PromptOptimizer(
    private val config: PromptOptimizationConfig = PromptOptimizationConfig(),
) {
    fun optimize(draft: PromptDraft): PromptDraft {
        val variables = config.staticVariables + draft.templateVariables
        val optimizePart = { part: ContentPart ->
            if (part is ContentPart.Text) ContentPart.Text(resolveVariables(compress(part.text), variables)) else part
        }
        // 平坦な[PromptDraft.input]とrole付きの[PromptDraft.messages]へ同じ変換を掛ける。
        // 片方だけ最適化すると、Providerへ渡る内容とトークン計上がずれる（ADR-0031）。
        return draft.copy(
            input = draft.input.map(optimizePart),
            messages = draft.messages.map { it.copy(content = it.content.map(optimizePart)) },
        )
    }

    private fun compress(text: String): String {
        if (!config.collapseWhitespace) return text
        return text.trim().replace(WHITESPACE, " ")
    }

    private fun resolveVariables(
        text: String,
        variables: Map<String, String>,
    ): String =
        VARIABLE_PLACEHOLDER.replace(text) { match ->
            val name = match.groupValues[1]
            variables[name] ?: match.value
        }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val VARIABLE_PLACEHOLDER = Regex("\\{\\{\\s*(\\w+)\\s*\\}\\}")
    }
}
