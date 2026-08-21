package apap.domain.port

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TenantId

/**
 * 02_システム仕様.md 2.5.2 ハードフィルタ(f): テナントのCapability/Model利用権限。
 * テナント契約・サブスクリプション管理自体は範囲外であり、Routing候補組立時に
 * 「このテナントはこのCapability/Modelを使ってよいか」を読むための最小限の読み取り専用口のみを定義する。
 */
interface TenantEntitlementRepository {
    fun isPermitted(
        tenantId: TenantId,
        capabilityId: CapabilityId,
        modelId: ModelId,
    ): Boolean
}
