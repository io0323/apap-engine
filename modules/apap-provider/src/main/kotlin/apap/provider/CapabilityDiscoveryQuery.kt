package apap.provider

import apap.domain.model.capability.CapabilityDefinition
import apap.domain.model.routing.PolicyScope
import apap.domain.model.vo.TenantId
import apap.domain.port.CapabilityRepository
import apap.domain.port.PolicyRepository
import apap.domain.service.routing.PolicyResolutionService

/**
 * 03_基本設計.md 3.5 `CapabilityDiscoveryQuery` / 05_シーケンス設計.md 5.9: テナントが利用可能な
 * Capability一覧を返すQuery。deny解決の4階層マージアルゴリズムは[PolicyResolutionService]に完全に
 * 委譲し、本クラスはその結果からcapability_id単位で除外するだけに徹する
 * （[apap.domain.service.routing.PolicyResolutionService.EffectivePolicy.isDenied]はRouting候補
 * （provider/model）単位の判定を要するため、Provider/Modelを介さないCapability一覧では使えず、
 * denyRuleのtarget.capabilitiesを直接参照する）。
 */
class CapabilityDiscoveryQuery(
    private val capabilityRepository: CapabilityRepository,
    private val policyRepository: PolicyRepository,
) {
    fun listCapabilities(tenantId: TenantId): List<CapabilityDefinition> {
        val policies = policyRepository.findEffective(tenantId, workflowId = null)
        val effectivePolicy =
            PolicyResolutionService.resolve(
                platform = policies.filter { it.scope == PolicyScope.PLATFORM }.flatMap { it.rules },
                tenant = policies.filter { it.scope == PolicyScope.TENANT }.flatMap { it.rules },
                workflow = emptyList(),
                user = emptyList(),
            )
        val deniedCapabilities =
            effectivePolicy.denyRules
                .flatMap { it.target.capabilities }
                .toSet()
        return capabilityRepository.listAll().filterNot { it.capabilityId in deniedCapabilities }
    }
}
