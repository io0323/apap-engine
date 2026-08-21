package apap.domain.service.provider

import apap.domain.model.modelcatalog.AliasTarget
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.RequestId

/**
 * 04_ドメイン設計.md 4.6: Alias targets[]の重みに基づく決定的抽選（requestIdハッシュで再現性確保）。
 * `String.hashCode()`はJava言語仕様で計算式が固定されているが、依存を明示するためここでは
 * 独自の安定ハッシュ関数を用いる。
 */
object CanaryResolutionService {
    fun resolve(
        targets: List<AliasTarget>,
        requestId: RequestId,
    ): ModelId {
        require(targets.isNotEmpty()) { "targets must not be empty" }
        val totalWeight = targets.sumOf { it.weight }
        require(totalWeight > 0) { "total weight must be positive: $totalWeight" }

        val bucket = (stableHash(requestId.value) % totalWeight + totalWeight) % totalWeight
        var cumulative = 0
        for (target in targets) {
            cumulative += target.weight
            if (bucket < cumulative) return target.modelId
        }
        return targets.last().modelId
    }

    private fun stableHash(value: String): Long {
        var hash = 0L
        for (ch in value) {
            hash = hash * MULTIPLIER + ch.code
        }
        return hash
    }

    private const val MULTIPLIER = 31L
}
