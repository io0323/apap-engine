package apap.runtime

import apap.adapter.spi.SecretAccessor
import apap.domain.model.modelcatalog.AliasTarget
import apap.domain.model.modelcatalog.Model
import apap.domain.model.modelcatalog.ModelAlias
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.Provider
import apap.domain.model.routing.RoutingPolicy
import apap.domain.model.vo.AliasId
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import apap.domain.port.AliasRepository
import apap.domain.port.ModelRepository
import apap.domain.port.PolicyRepository
import apap.domain.port.ProviderRepository
import apap.provider.AdapterRegistry
import apap.provider.CapabilityRegistry
import apap.provider.ModelManager
import apap.provider.ProviderManager
import apap.provider.RegisterModelCommand
import apap.provider.RegisterProviderCommand
import apap.provider.ValidationOutcome

/**
 * 03_基本設計.md 3.10 `AdminFacade`（CLAUDE.md用語対応表: `ApapAdmin`）。[ApapEngine.admin]経由で
 * 取得する管理API入口。3.10は`providers()/models()/aliases()/policies()/quotas()/analytics()`を
 * 挙げるが、本タスクの指示（P9着手前レビュー）はProvider/Model/Alias/Policy操作のみを要求するため、
 * quotas/analyticsは対象外とする（要件充足に影響しない実装判断のためADR化せずここに根拠を記す。
 * 既存の`QuotaPolicyRepository`/`UsageRepository`をラップするだけの薄いI/Fであり、必要になった時点で
 * 追加すればよい）。
 *
 * 各Managerの既存メソッドをそのまま公開する薄いラッパー（判断ロジックはManager/Repository側に
 * 委譲）。
 */
@Suppress("LongParameterList")
class ApapAdmin internal constructor(
    private val providerManager: ProviderManager,
    private val modelManager: ModelManager,
    private val providerRepository: ProviderRepository,
    private val modelRepository: ModelRepository,
    private val aliasRepository: AliasRepository,
    private val policyRepository: PolicyRepository,
    @Suppress("UnusedPrivateProperty") private val adapterRegistry: AdapterRegistry,
    private val capabilityRegistry: CapabilityRegistry,
    /**
     * [ApapEngineBuilder.secretStore]の解決口。Provider登録後、実際に`ProviderAdapter.initialize`を
     * 呼び出す（Credential解決を伴う）のは埋込ホスト自身の責務（15.1 Step4以降、本タスクの範囲外
     * ——requirements-matrix.md FR-PRV-004参照）のため、そのために必要な[SecretAccessor]をここで
     * 公開する。
     */
    val secretAccessor: SecretAccessor,
) {
    val providers = ProviderAdmin(providerManager, providerRepository)
    val models = ModelAdmin(modelManager, modelRepository, aliasRepository)
    val policies = PolicyAdmin(policyRepository)

    /**
     * Capabilityスキーマの登録・検証（FR-CAP-017 / NFR-EXT-003）。
     * P11-F3では`CapabilityRegistry`が本番配線のどこからも生成されておらず、
     * 参照側（`GET /v1/capabilities`）だけが動いて登録側が機能していなかった。
     */
    val capabilities = capabilityRegistry
}

class ProviderAdmin internal constructor(
    private val manager: ProviderManager,
    private val repository: ProviderRepository,
) {
    fun register(command: RegisterProviderCommand): Provider = manager.register(command)

    fun beginValidation(providerId: ProviderId): Provider = manager.beginValidation(providerId)

    suspend fun completeValidation(providerId: ProviderId): ValidationOutcome = manager.completeValidation(providerId)

    fun enable(
        providerId: ProviderId,
        reason: String,
    ): Provider = manager.enable(providerId, reason)

    fun drain(
        providerId: ProviderId,
        reason: String,
    ): Provider = manager.drain(providerId, reason)

    fun completeDraining(
        providerId: ProviderId,
        reason: String,
    ): Provider = manager.completeDraining(providerId, reason)

    fun delete(providerId: ProviderId): Provider = manager.delete(providerId)

    fun findById(providerId: ProviderId): Provider? = repository.findById(providerId)

    fun list(): List<Provider> = repository.findAll()
}

class ModelAdmin internal constructor(
    private val manager: ModelManager,
    private val modelRepository: ModelRepository,
    private val aliasRepository: AliasRepository,
) {
    fun register(command: RegisterModelCommand): Model = manager.register(command)

    fun changeStatus(
        modelId: ModelId,
        target: ModelStatus,
    ): Model = manager.changeStatus(modelId, target)

    fun assignAlias(
        tenantId: TenantId,
        aliasId: AliasId,
        name: String,
        targets: List<AliasTarget>,
    ): ModelAlias = manager.assignAlias(tenantId, aliasId, name, targets)

    fun setCanaryWeight(
        tenantId: TenantId,
        name: String,
        weights: Map<ModelId, Int>,
    ): ModelAlias = manager.setCanaryWeight(tenantId, name, weights)

    fun findById(modelId: ModelId): Model? = modelRepository.findById(modelId)

    fun findByProvider(providerId: ProviderId): List<Model> = modelRepository.findByProvider(providerId)

    fun findByCapability(capabilityId: CapabilityId): List<Model> = modelRepository.findByCapability(capabilityId)

    fun findAlias(
        tenantId: TenantId,
        name: String,
    ): ModelAlias? = aliasRepository.findByName(tenantId, name)
}

class PolicyAdmin internal constructor(
    private val repository: PolicyRepository,
) {
    fun save(policy: RoutingPolicy) = repository.save(policy)

    fun findById(policyId: String): RoutingPolicy? = repository.findById(policyId)

    fun findEffective(
        tenantId: TenantId?,
        workflowId: String?,
    ): List<RoutingPolicy> = repository.findEffective(tenantId, workflowId)
}
