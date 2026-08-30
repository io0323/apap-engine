package apap.infrastructure.persistence.inmemory

import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import apap.domain.port.QuotaSnapshotRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * [QuotaSnapshotRepository]の本番用In-Memory実装。既定は無制限（[Int.MAX_VALUE]）。
 * [QuotaSnapshotRepository]自体は読み取り専用ポートのため（Routing候補組立のハードフィルタ用、
 * 予約・確定を伴う本格的な集計はapap-costの責務）、値を投入する経路はPortの外側の
 * 公開メソッド[setRemaining]として提供する（呼び出し元はQuota/Cost管理側の配線を想定）。
 */
class InMemoryQuotaSnapshotRepository : QuotaSnapshotRepository {
    private val remainingByKey = ConcurrentHashMap<Triple<TenantId, ProviderId, ModelId>, Int>()

    override fun remaining(
        tenantId: TenantId,
        providerId: ProviderId,
        modelId: ModelId,
    ): Int = remainingByKey[Triple(tenantId, providerId, modelId)] ?: Int.MAX_VALUE

    fun setRemaining(
        tenantId: TenantId,
        providerId: ProviderId,
        modelId: ModelId,
        remaining: Int,
    ) {
        remainingByKey[Triple(tenantId, providerId, modelId)] = remaining
    }
}
