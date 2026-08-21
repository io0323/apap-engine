package apap.domain.service.routing

import apap.domain.model.execution.CbState
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.ProviderHealthStatus
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region

/**
 * 02_システム仕様.md 2.5.2 step2: ハードフィルタ（a〜g、順序固定）。
 * [RoutingDomainService]から分離し、各条件を独立関数として単体テスト可能にする（TooManyFunctions回避も兼ねる）。
 */
object RoutingHardFilters {
    /** a. Provider Status = ACTIVE かつ Model Status = ACTIVE（TESTINGはCanary対象時のみ）。 */
    fun passesStatusFilter(candidate: Candidate): Boolean =
        candidate.providerStatus == ProviderStatus.ACTIVE &&
            (
                candidate.modelStatus == ModelStatus.ACTIVE ||
                    (candidate.modelStatus == ModelStatus.TESTING && candidate.isCanaryEligible)
            )

    /** b. Circuit Breaker が OPEN でない。 */
    fun passesCircuitBreakerFilter(candidate: Candidate): Boolean = candidate.cbState != CbState.OPEN

    /** c. Provider Health ≠ DOWN。 */
    fun passesHealthFilter(candidate: Candidate): Boolean = candidate.health != ProviderHealthStatus.DOWN

    /** d. Region制約（constraints.region ⊆ model.regions）。regionRequirementがnullなら制約なし。 */
    fun passesRegionFilter(
        candidate: Candidate,
        regionRequirement: Region?,
    ): Boolean = regionRequirement == null || regionRequirement in candidate.supportedRegions

    /** e. Policy禁止事項（4階層すべてのdenyを評価）。判定自体はPolicyResolutionServiceの結果を用いる。 */
    fun passesPolicyFilter(
        candidate: Candidate,
        isDenied: (Candidate) -> Boolean,
    ): Boolean = !isDenied(candidate)

    /** f. テナントのCapability/Model利用権限。 */
    fun passesPermissionFilter(candidate: Candidate): Boolean = candidate.hasPermission

    /** g. Quota残量 > 0。 */
    fun passesQuotaFilter(candidate: Candidate): Boolean = candidate.quotaRemaining

    /** RoutingConstraints.excludeProviders（2.5.1）。2.5.2のa〜gには明示されないが、制約として適用する。 */
    fun passesExcludeProvidersFilter(
        candidate: Candidate,
        excludeProviders: Set<ProviderId>,
    ): Boolean = candidate.providerId !in excludeProviders

    /** a〜gを順序固定で適用する。 */
    fun apply(
        candidates: List<Candidate>,
        regionRequirement: Region?,
        excludeProviders: Set<ProviderId> = emptySet(),
        isDenied: (Candidate) -> Boolean = { false },
    ): List<Candidate> =
        candidates
            .filter(::passesStatusFilter)
            .filter(::passesCircuitBreakerFilter)
            .filter(::passesHealthFilter)
            .filter { passesRegionFilter(it, regionRequirement) }
            .filter { passesExcludeProvidersFilter(it, excludeProviders) }
            .filter { passesPolicyFilter(it, isDenied) }
            .filter(::passesPermissionFilter)
            .filter(::passesQuotaFilter)
}
