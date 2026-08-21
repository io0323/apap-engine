package apap.domain.model.vo

/** 04_ドメイン設計.md 4.4: (providerId, modelId)。02_システム仕様.md 2.13: Circuit Breakerの単位。 */
data class CbKey(
    val providerId: ProviderId,
    val modelId: ModelId,
)
