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
        val optimizedInput =
            draft.input.map { part ->
                if (part !is ContentPart.Text) {
                    part
                } else {
                    ContentPart.Text(resolveVariables(compress(part.text), variables))
                }
            }
        return draft.copy(input = optimizedInput)
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
