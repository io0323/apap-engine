package apap.domain.model.modelcatalog

import apap.domain.model.IllegalStateTransitionException
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.Modality
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region

/** 09_状態遷移図.md 9.2。 */
enum class ModelStatus { REGISTERED, TESTING, ACTIVE, DEPRECATED, RETIRED }

class IllegalModelStateTransitionException(
    from: ModelStatus,
    to: ModelStatus,
) : IllegalStateTransitionException("Illegal Model transition: $from -> $to")

class ModelStillReferencedByAliasException(
    modelId: ModelId,
    activeAliasReferenceCount: Int,
) : IllegalStateTransitionException(
        "Model $modelId cannot be RETIRED while referenced by $activeAliasReferenceCount alias target(s)",
    )

/** 04_ドメイン設計.md 4.3.2 ModelCapability（Entity）: capabilityIdはCapability Registryに存在。 */
data class ModelCapability(
    val capabilityId: CapabilityId,
    /** 構造化していない制約（最大入力、並列tool数等）。機械的な判断には使わない。 */
    val constraints: Map<String, String> = emptyMap(),
    /**
     * 受け付けられる入力modality。04_ドメイン設計.md 4.3.2の`constraints（対応modality…）`のうち、
     * **Routingがハードフィルタとして読む**部分を型として切り出したもの（ADR-0039）。
     *
     * 空集合は「未申告」であり「非対応」ではない。未申告のModelは従来どおり候補に残る
     * （申告のあるModelだけを絞り込む）。[constraints]の自由文字列のままではRoutingが
     * 解釈できず、実際に**誰も読んでいなかった**。
     *
     * 既存の位置引数呼出を壊さないよう**末尾**に置くこと（P13で同じ形の破壊を経験している）。
     */
    val supportedInputModalities: Set<Modality> = emptySet(),
)

/**
 * 04_ドメイン設計.md 4.3.2 Model Aggregate（Root）。
 * 状態遷移は09_状態遷移図.md 9.2のみ。RETIREDへの遷移はAlias参照ゼロが条件
 * （Alias参照数はModelAlias Aggregate側の情報のため、[retire]の引数として呼び出し側から供給する）。
 */
data class Model(
    val modelId: ModelId,
    val providerId: ProviderId,
    val modelName: String,
    val version: String,
    val capabilities: List<ModelCapability> = emptyList(),
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val regions: Set<Region>,
    val status: ModelStatus = ModelStatus.REGISTERED,
    val priority: Int,
) {
    init {
        require(modelName.isNotBlank()) { "modelName must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
        require(contextWindow > 0) { "contextWindow must be positive: $contextWindow" }
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive: $maxOutputTokens" }
        require(maxOutputTokens <= contextWindow) {
            "maxOutputTokens must not exceed contextWindow: $maxOutputTokens > $contextWindow"
        }
        require(priority in MIN_PRIORITY..MAX_PRIORITY) { "priority must be within 1..100: $priority" }
    }

    /** RETIRED以外への遷移。RETIREDは[retire]経由でのみ許可する。 */
    fun transitionTo(target: ModelStatus): Model {
        require(target != ModelStatus.RETIRED) { "Use retire(activeAliasReferenceCount) to transition to RETIRED" }
        return doTransition(target)
    }

    /** 4.3.2不変条件: RETIREDへの遷移はAlias参照ゼロが条件。 */
    fun retire(activeAliasReferenceCount: Int): Model {
        if (activeAliasReferenceCount != 0) {
            throw ModelStillReferencedByAliasException(modelId, activeAliasReferenceCount)
        }
        return doTransition(ModelStatus.RETIRED)
    }

    private fun doTransition(target: ModelStatus): Model {
        val allowed = ALLOWED_TRANSITIONS[status].orEmpty()
        if (target !in allowed) {
            throw IllegalModelStateTransitionException(status, target)
        }
        return copy(status = target)
    }

    companion object {
        // 9.2: REGISTERED->TESTING->ACTIVE、TESTING->REGISTERED(差戻し)、
        //      ACTIVE->DEPRECATED->ACTIVE(復帰)、DEPRECATED->RETIRED
        private val ALLOWED_TRANSITIONS: Map<ModelStatus, Set<ModelStatus>> =
            mapOf(
                ModelStatus.REGISTERED to setOf(ModelStatus.TESTING),
                ModelStatus.TESTING to setOf(ModelStatus.ACTIVE, ModelStatus.REGISTERED),
                ModelStatus.ACTIVE to setOf(ModelStatus.DEPRECATED),
                ModelStatus.DEPRECATED to setOf(ModelStatus.ACTIVE, ModelStatus.RETIRED),
                ModelStatus.RETIRED to emptySet(),
            )

        private const val MIN_PRIORITY = 1
        private const val MAX_PRIORITY = 100
    }
}
