package apap.domain.port

import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId

/**
 * 02_システム仕様.md 2.5.2 ハードフィルタ(g): Quota残量 > 0 の候補のみ残す。
 * 予約(reserve)/確定(commit)を伴う本格的なQuota Manager（期間別使用量集計・上限判定）は
 * apap-costの責務であり本Portの範囲外。ここではRouting候補組立時に残量を読むための
 * 最小限の読み取り専用口のみを定義する。
 */
interface QuotaSnapshotRepository {
    fun remaining(
        tenantId: TenantId,
        providerId: ProviderId,
        modelId: ModelId,
    ): Int
}
