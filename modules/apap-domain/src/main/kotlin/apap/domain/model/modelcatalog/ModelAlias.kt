package apap.domain.model.modelcatalog

import apap.domain.model.vo.AliasId
import apap.domain.model.vo.ModelId

/** 04_ドメイン設計.md 4.3.3 targets[]{modelId, weight}。 */
data class AliasTarget(
    val modelId: ModelId,
    val weight: Int,
) {
    init {
        require(weight in MIN_WEIGHT..MAX_WEIGHT) { "weight must be within 0..100: $weight" }
    }

    companion object {
        private const val MIN_WEIGHT = 0
        private const val MAX_WEIGHT = 100
    }
}

/**
 * 04_ドメイン設計.md 4.3.3 ModelAlias Aggregate（Root）。
 * 不変条件: weight合計=100。nameのテナントスコープ一意性はRepository（AliasRepository.findByName）が担う。
 */
data class ModelAlias(
    val aliasId: AliasId,
    val name: String,
    val targets: List<AliasTarget>,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(targets.isNotEmpty()) { "ModelAlias must have at least one target" }
        require(targets.map { it.modelId }.toSet().size == targets.size) {
            "ModelAlias targets must not reference the same modelId more than once"
        }
        val totalWeight = targets.sumOf { it.weight }
        require(totalWeight == TOTAL_WEIGHT) { "ModelAlias target weights must sum to 100: got $totalWeight" }
    }

    companion object {
        private const val TOTAL_WEIGHT = 100

        /**
         * 4.3.3: 「target ModelはACTIVEまたはTESTING」の検証。ModelのステータスはModel Aggregate側の
         * 情報（他Aggregateの状態）であるため、コンストラクタでは検証せず、呼び出し側（Domain Service /
         * Application層）がModelRepository等から解決したステータスを`modelStatus`として供給する
         * ファクトリを別途用意する。
         */
        fun createWithModelStatusCheck(
            aliasId: AliasId,
            name: String,
            targets: List<AliasTarget>,
            modelStatus: (ModelId) -> ModelStatus,
        ): ModelAlias {
            targets.forEach { target ->
                val status = modelStatus(target.modelId)
                require(status == ModelStatus.ACTIVE || status == ModelStatus.TESTING) {
                    "AliasTarget model must be ACTIVE or TESTING: modelId=${target.modelId}, status=$status"
                }
            }
            return ModelAlias(aliasId, name, targets)
        }
    }
}
