package apap.domain.port

import apap.domain.model.cost.QuotaPolicy
import apap.domain.model.vo.TenantId

/**
 * `QuotaPolicy`（04_ドメイン設計.md 4.3.5 / 12_ER図.md QUOTA_POLICY）のRepository。永続化・管理API向け。
 * `apap.execution.ExecutionEngine`実行時の解決口である`quotaPolicyProvider: (TenantId) -> QuotaPolicy?`
 * （関数型）とは役割が異なり併存する: 本Repositoryは登録・一覧・更新のCRUD、
 * `quotaPolicyProvider`は実行時の高速な解決口（既定実装は本Repository経由で構成できる）。
 */
interface QuotaPolicyRepository {
    fun findByTenant(tenantId: TenantId): List<QuotaPolicy>

    fun save(policy: QuotaPolicy)
}
