package apap.domain.port

import apap.domain.model.conversation.Memory
import apap.domain.model.conversation.MemoryScope
import apap.domain.model.vo.TenantId

interface MemoryRepository {
    fun store(memory: Memory)

    /**
     * 02_システム仕様.md 2.17: 類似検索（top-k、類似度閾値）。
     * [Memory]のKDoc（4.3.5「scopeを跨ぐ参照不可」）は本メソッドの呼び出し側がscope引数で
     * 絞り込むことを前提としていたが、当初のシグネチャに[tenantId]/[scopes]が無く、
     * テナント境界すら実施できていなかった（マルチテナント分離の欠落）。両方を必須引数として
     * 追加し、実装側（テナント一致 かつ [scopes]のいずれかに一致するもののみ対象）で担保する。
     */
    fun searchByVector(
        tenantId: TenantId,
        scopes: Set<MemoryScope>,
        vector: List<Double>,
        topK: Int,
        threshold: Double,
    ): List<Memory>

    fun delete(memoryId: String)
}
