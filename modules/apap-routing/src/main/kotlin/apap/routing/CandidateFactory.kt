package apap.routing

import apap.domain.model.execution.CbState
import apap.domain.model.modelcatalog.Model
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.Provider
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.CbKey
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import apap.domain.port.AliasRepository
import apap.domain.port.CircuitBreakerStateRepository
import apap.domain.port.Clock
import apap.domain.port.HealthLatencyStatsRepository
import apap.domain.port.ModelRepository
import apap.domain.port.ProviderRepository
import apap.domain.port.QuotaSnapshotRepository
import apap.domain.port.TenantEntitlementRepository
import apap.domain.service.provider.CanaryResolutionService
import apap.domain.service.routing.Candidate

/**
 * 03_基本設計.md 3.6 `CandidateFactory`: Model+Provider+Health+Price → Candidate。
 * 候補抽出（Capability Resolverの責務、02_システム仕様.md 2.5.2 step1）と、Health/CB/Quota/Policy許諾等
 * I/Oを要する事実の収集を担う。判断ロジック（フィルタ・スコア）は一切持たず、[Candidate]を組み立てるだけ。
 *
 * Provider/Model状態・Healthは[RoutingCandidateCache]（イベント駆動、`null`ならまだ観測していない）を
 * 優先し、キャッシュに存在しない場合のみRepositoryへフォールバックする（起動直後や、キャッシュ配線前に
 * 保存された既存データでも正しく動作させるための安全網）。
 */
@Suppress("LongParameterList")
class CandidateFactory(
    private val providerRepository: ProviderRepository,
    private val modelRepository: ModelRepository,
    private val aliasRepository: AliasRepository,
    private val circuitBreakerStateRepository: CircuitBreakerStateRepository,
    private val healthLatencyStatsRepository: HealthLatencyStatsRepository,
    private val quotaSnapshotRepository: QuotaSnapshotRepository,
    private val tenantEntitlementRepository: TenantEntitlementRepository,
    private val costEstimator: CostEstimator,
    private val cache: RoutingCandidateCache,
    private val clock: Clock,
) {
    /** [RoutingEngine]がRoutingDecisionへ伝播させる、S_cost算出がスタブかどうかの自己申告。 */
    val costEstimationStubbed: Boolean get() = costEstimator.isStub

    fun build(
        capabilityId: CapabilityId,
        modelAlias: String?,
        tenantId: TenantId,
        requestId: RequestId,
    ): List<Candidate> {
        val modelsWithCanaryEligibility = resolveModels(capabilityId, modelAlias, tenantId, requestId)
        return modelsWithCanaryEligibility.mapNotNull { (model, isCanaryEligible) ->
            val provider = providerRepository.findById(model.providerId) ?: return@mapNotNull null
            toCandidate(provider, model, isCanaryEligible, capabilityId, tenantId)
        }
    }

    /** model_alias指定時はCanary抽選で1件に確定する（2.5.1）。未指定ならCapability一致Model全件。 */
    private fun resolveModels(
        capabilityId: CapabilityId,
        modelAlias: String?,
        tenantId: TenantId,
        requestId: RequestId,
    ): List<Pair<Model, Boolean>> =
        if (modelAlias == null) {
            modelRepository.findByCapability(capabilityId).map { it to false }
        } else {
            resolveViaAlias(modelAlias, tenantId, requestId)
        }

    private fun resolveViaAlias(
        modelAlias: String,
        tenantId: TenantId,
        requestId: RequestId,
    ): List<Pair<Model, Boolean>> {
        val alias = aliasRepository.findByName(tenantId, modelAlias) ?: return emptyList()
        val resolvedModelId = CanaryResolutionService.resolve(alias.targets, requestId)
        val model = modelRepository.findById(resolvedModelId)
        return listOfNotNull(model?.let { it to (it.status == ModelStatus.TESTING) })
    }

    private fun toCandidate(
        provider: Provider,
        model: Model,
        isCanaryEligible: Boolean,
        capabilityId: CapabilityId,
        tenantId: TenantId,
    ): Candidate {
        val now = clock.now()
        val cbKey = CbKey(provider.providerId, model.modelId)
        val cbState = circuitBreakerStateRepository.find(cbKey)?.state ?: CbState.CLOSED
        val healthSnapshot = healthLatencyStatsRepository.snapshot(provider.providerId, model.modelId, now)
        val quotaRemaining = quotaSnapshotRepository.remaining(tenantId, provider.providerId, model.modelId) > 0
        val hasPermission = tenantEntitlementRepository.isPermitted(tenantId, capabilityId, model.modelId)
        return Candidate(
            providerId = provider.providerId,
            modelId = model.modelId,
            providerStatus = cache.providerStatus(provider.providerId) ?: provider.status,
            modelStatus = cache.modelStatus(model.modelId) ?: model.status,
            isCanaryEligible = isCanaryEligible,
            cbState = cbState,
            health = cache.providerHealth(provider.providerId) ?: ProviderHealthStatus.UP,
            supportedRegions = model.regions,
            estimatedCost = costEstimator.estimate(provider.providerId, model.modelId),
            p90LatencyMs = healthSnapshot.p90LatencyMs.toDouble(),
            successRate = healthSnapshot.successRate,
            providerPriority = provider.priority,
            modelPriority = model.priority,
            hasPermission = hasPermission,
            quotaRemaining = quotaRemaining,
        )
    }
}
