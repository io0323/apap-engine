package apap.prompt

import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ErrorCode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion

/** [PromptValidator.validate]が検証失敗時に投げる。13.4のPROMPT_VALIDATION_FAILED（400）に対応する。 */
class PromptValidationFailedException(
    message: String,
) : RuntimeException(message) {
    val errorCode: ErrorCode = ErrorCode.PROMPT_VALIDATION_FAILED
}

/**
 * 16_拡張ポイント.md 16.7「Validation規則」。CLAUDE.md不変条件7に従いすべて設定可能。
 * [injectionPatterns]の既定値は典型的なプロンプトインジェクション表現の粗い検出であり、
 * 完全性を主張しない（テナント/業界別の強化はSPI差替[apap.prompt.PromptValidator]自体の
 * 差替、または[forbiddenPatterns]/[injectionPatterns]の設定変更で行う）。
 */
data class PromptValidationConfig(
    val maxInputChars: Int = 100_000,
    val forbiddenPatterns: List<Regex> = emptyList(),
    val injectionPatterns: List<Regex> = DEFAULT_INJECTION_PATTERNS,
    val enforceOutputSchema: Boolean = true,
) {
    init {
        require(maxInputChars > 0) { "maxInputChars must be positive: $maxInputChars" }
    }

    companion object {
        val DEFAULT_INJECTION_PATTERNS: List<Regex> =
            listOf(
                Regex("ignore (all |any )?(previous|prior|above) instructions", RegexOption.IGNORE_CASE),
                Regex("disregard (all |any )?(previous|prior|above) (instructions|prompts?)", RegexOption.IGNORE_CASE),
                Regex("reveal (your |the )?(system|hidden) prompt", RegexOption.IGNORE_CASE),
            )
    }
}

/**
 * 16_拡張ポイント.md 16.7 / 02_システム仕様.md 2.15周辺: サイズ上限、禁止パターン、
 * インジェクション検査、出力Schema整合を行う。Schemaは「構文として妥当なJSON Schemaか」のみを
 * 検証する（応答自体がまだ存在しないため、応答本体との整合検証はResponse Mapper側の責務）。
 */
class PromptValidator(
    private val config: PromptValidationConfig = PromptValidationConfig(),
) {
    fun validate(draft: PromptDraft) {
        val text = textOf(draft.input)
        if (text.length > config.maxInputChars) {
            throw PromptValidationFailedException(
                "input exceeds maxInputChars: ${text.length} > ${config.maxInputChars}",
            )
        }
        rejectIfMatches(text, config.forbiddenPatterns, "a forbidden pattern")
        rejectIfMatches(text, config.injectionPatterns, "a potential prompt-injection pattern")
        if (config.enforceOutputSchema) {
            draft.outputSchema?.let(::validateOutputSchemaSyntax)
        }
    }

    private fun rejectIfMatches(
        text: String,
        patterns: List<Regex>,
        description: String,
    ) {
        val matched = patterns.firstOrNull { it.containsMatchIn(text) }
        if (matched != null) {
            throw PromptValidationFailedException("input matches $description: ${matched.pattern}")
        }
    }

    private fun validateOutputSchemaSyntax(schemaJson: String) {
        val result = runCatching { SCHEMA_FACTORY.getSchema(schemaJson) }
        if (result.isFailure) {
            throw PromptValidationFailedException(
                "outputSchema is not syntactically valid JSON Schema: ${result.exceptionOrNull()?.message}",
            )
        }
    }

    private fun textOf(parts: List<ContentPart>): String =
        parts.joinToString(separator = " ") { part -> if (part is ContentPart.Text) part.text else "" }

    private companion object {
        val SCHEMA_FACTORY: JsonSchemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    }
}
