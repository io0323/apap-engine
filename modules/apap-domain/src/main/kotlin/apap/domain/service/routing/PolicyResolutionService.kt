package apap.domain.service.routing

import apap.domain.model.routing.PolicyEffect
import apap.domain.model.routing.PolicyOverride
import apap.domain.model.routing.PolicyRule
import apap.domain.model.routing.PolicyRuleTarget
import apap.domain.model.vo.CapabilityId

/**
 * PolicyRuleの評価結果。denyRulesは4階層すべてから累積したDENYルール（上位は下位で解除不可）。
 * overrideは4階層をPLATFORM→TENANT→WORKFLOW→USERの順にマージした「後勝ち」の結果。
 * `condition`（Expression、例: cost.estimated > 0.05）の評価はドメイン層の範囲外
 * （式評価エンジンを要するため）とし、ここでは`target`のみを一致条件として扱う。
 */
data class EffectivePolicy(
    val denyRules: List<PolicyRule>,
    val allowRules: List<PolicyRule>,
    val override: PolicyOverride,
) {
    fun isDenied(
        candidate: Candidate,
        capabilityId: CapabilityId,
    ): Boolean = denyRules.any { rule -> targetMatches(rule.target, candidate, capabilityId) }

    private fun targetMatches(
        target: PolicyRuleTarget,
        candidate: Candidate,
        capabilityId: CapabilityId,
    ): Boolean {
        val providerMatches = target.providers.isEmpty() || candidate.providerId in target.providers
        val modelMatches = target.models.isEmpty() || candidate.modelId in target.models
        val capabilityMatches = target.capabilities.isEmpty() || capabilityId in target.capabilities
        val regionMatches = target.regions.isEmpty() || candidate.supportedRegions.any { it in target.regions }
        return providerMatches && modelMatches && capabilityMatches && regionMatches
    }
}

/**
 * 04_ドメイン設計.md 4.6 / 03_基本設計.md 3.14: 4階層Policyのマージ・競合解決。
 * 評価: PLATFORM→TENANT→WORKFLOW→USERの順にマージ。DENYは累積（下位で解除不可）。overrideは後勝ち。
 * 副作用なしの純粋関数。
 */
object PolicyResolutionService {
    fun resolve(
        platform: List<PolicyRule>,
        tenant: List<PolicyRule>,
        workflow: List<PolicyRule>,
        user: List<PolicyRule>,
    ): EffectivePolicy {
        val precedenceOrder = platform + tenant + workflow + user
        val denyRules = precedenceOrder.filter { it.effect == PolicyEffect.DENY }
        val allowRules = precedenceOrder.filter { it.effect == PolicyEffect.ALLOW }
        val override = mergeOverrides(precedenceOrder)
        return EffectivePolicy(denyRules, allowRules, override)
    }

    /** overrideは後勝ち: precedenceOrder（PLATFORM→USER）を順に適用し、非nullな項目で上書きする。 */
    private fun mergeOverrides(precedenceOrder: List<PolicyRule>): PolicyOverride {
        var weights: Map<String, Double>? = null
        var fallbackDepth: Int? = null
        var cacheTtlSeconds: Long? = null
        var retryMax: Int? = null
        for (rule in precedenceOrder) {
            val override = rule.override ?: continue
            override.weights?.let { weights = it }
            override.fallbackDepth?.let { fallbackDepth = it }
            override.cacheTtlSeconds?.let { cacheTtlSeconds = it }
            override.retryMax?.let { retryMax = it }
        }
        return PolicyOverride(weights, fallbackDepth, cacheTtlSeconds, retryMax)
    }
}
