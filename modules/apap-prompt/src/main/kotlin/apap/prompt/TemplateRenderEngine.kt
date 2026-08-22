package apap.prompt

import apap.domain.model.prompt.PromptTemplate

/** [TemplateRenderEngine.render]が、必須かつ未解決のテンプレート変数がある場合に送出する。 */
class MissingTemplateVariableException(
    name: String,
) : RuntimeException("Missing required template variable: $name")

/**
 * 16_拡張ポイント.md 16.7「Template関数」: カスタム変数解決（例: 現在時刻、テナント語彙）。
 * `name`と`resolve`の2つの抽象メンバーを持つため`fun interface`（SAM、抽象メンバー1つのみ許可）
 * にはできない。
 */
interface TemplateFunction {
    val name: String

    fun resolve(ctx: PromptStageContext): String
}

/**
 * [PromptTemplate.body]の描画エンジン。文法は意図的に最小限（16.7が挙げる用途を満たす範囲）:
 * - `{{name}}` 変数置換
 * - `{{#if name}}...{{/if}}` / `{{#unless name}}...{{/unless}}` 単純な条件分岐（入れ子非対応）
 *
 * 変数の解決優先順位: 呼出側[render]の`variables`引数 > [functions]の動的解決 >
 * `PromptTemplate.variables`の`defaultValue`。requiredかつどれでも解決できない変数があれば
 * [MissingTemplateVariableException]を送出する。
 */
class TemplateRenderEngine(
    private val functions: List<TemplateFunction> = emptyList(),
) {
    fun render(
        template: PromptTemplate,
        variables: Map<String, String>,
        ctx: PromptStageContext,
    ): String {
        val functionValues = functions.associate { it.name to it.resolve(ctx) }
        val resolved = resolveVariables(template, variables, functionValues)
        return substitute(applyConditionals(template.body, resolved), resolved)
    }

    private fun resolveVariables(
        template: PromptTemplate,
        variables: Map<String, String>,
        functionValues: Map<String, String>,
    ): Map<String, String> {
        val resolved = (functionValues + variables).toMutableMap()
        template.variables.forEach { spec ->
            val value = resolved[spec.name] ?: spec.defaultValue
            if (value == null && spec.required) {
                throw MissingTemplateVariableException(spec.name)
            }
            resolved[spec.name] = value ?: ""
        }
        return resolved
    }

    private fun isTruthy(
        name: String,
        resolved: Map<String, String>,
    ): Boolean {
        val value = resolved[name]
        return !value.isNullOrBlank() && value != "false"
    }

    private fun applyConditionals(
        body: String,
        resolved: Map<String, String>,
    ): String {
        val afterIf =
            IF_BLOCK.replace(body) { match ->
                val (name, content) = match.destructured
                if (isTruthy(name, resolved)) content else ""
            }
        return UNLESS_BLOCK.replace(afterIf) { match ->
            val (name, content) = match.destructured
            if (!isTruthy(name, resolved)) content else ""
        }
    }

    private fun substitute(
        text: String,
        resolved: Map<String, String>,
    ): String = VARIABLE_PLACEHOLDER.replace(text) { match -> resolved[match.groupValues[1]] ?: match.value }

    private companion object {
        val IF_BLOCK = Regex("\\{\\{#if\\s+(\\w+)}}(.*?)\\{\\{/if}}", RegexOption.DOT_MATCHES_ALL)
        val UNLESS_BLOCK = Regex("\\{\\{#unless\\s+(\\w+)}}(.*?)\\{\\{/unless}}", RegexOption.DOT_MATCHES_ALL)
        val VARIABLE_PLACEHOLDER = Regex("\\{\\{\\s*(\\w+)\\s*}}")
    }
}
