package apap.domain.model.capability

import apap.domain.model.IllegalStateTransitionException
import apap.domain.model.vo.CapabilityId

enum class CapabilityDefinitionStatus { DRAFT, GA, DEPRECATED }

class SchemaBackwardCompatibilityRequiredException(
    capabilityId: CapabilityId,
) : IllegalStateTransitionException(
        "CapabilityDefinition $capabilityId is published; schema changes must be confirmed backward compatible",
    )

/**
 * 04_ドメイン設計.md 4.3.5 CapabilityDefinition Aggregate（Root）。
 * 不変条件: 公開後のスキーマ変更は後方互換のみ。JSON Schema同士の厳密な互換性判定はドメイン層の
 * 範囲外（I/Oを伴わない純粋な構造比較ロジックを持ち込む余地はあるが本実装では対象外）とし、
 * 呼び出し側が互換性を確認した結果を`confirmedBackwardCompatible`として明示的に渡す契約とする。
 */
data class CapabilityDefinition(
    val capabilityId: CapabilityId,
    val name: String,
    val inputSchema: String,
    val outputSchema: String,
    val streamable: Boolean,
    val status: CapabilityDefinitionStatus = CapabilityDefinitionStatus.DRAFT,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(inputSchema.isNotBlank()) { "inputSchema must not be blank" }
        require(outputSchema.isNotBlank()) { "outputSchema must not be blank" }
    }

    fun reviseSchema(
        newInputSchema: String,
        newOutputSchema: String,
        confirmedBackwardCompatible: Boolean,
    ): CapabilityDefinition {
        if (status != CapabilityDefinitionStatus.DRAFT && !confirmedBackwardCompatible) {
            throw SchemaBackwardCompatibilityRequiredException(capabilityId)
        }
        return copy(inputSchema = newInputSchema, outputSchema = newOutputSchema)
    }

    fun publish(): CapabilityDefinition = copy(status = CapabilityDefinitionStatus.GA)

    fun deprecate(): CapabilityDefinition = copy(status = CapabilityDefinitionStatus.DEPRECATED)
}
