package apap.infrastructure.persistence.inmemory

import apap.domain.model.conversation.Memory
import apap.domain.model.conversation.MemoryScope
import apap.domain.model.vo.TenantId
import apap.domain.port.MemoryRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * [MemoryRepository]の本番用In-Memory実装。02_システム仕様.md 2.17: top-k類似検索
 * （類似度=コサイン類似度、閾値以上のみ、類似度降順）。テナント一致 かつ `scopes`のいずれかに
 * 一致するもののみを検索対象とする（4.3.5「scopeを跨ぐ参照不可」）。
 *
 * ADR-0001: Vector Storeは当面RDBMS拡張で実装する（FR-CTX-004優先度S）。本実装は単一プロセス
 * 埋込利用の既定であり、`modules/apap-infrastructure-jdbc`のJDBC実装（pgvector等の拡張想定）が
 * 本番規模のベクトル検索を担う。
 */
class InMemoryMemoryRepository : MemoryRepository {
    private val memories = ConcurrentHashMap<String, Memory>()

    override fun store(memory: Memory) {
        memories[memory.memoryId] = memory
    }

    override fun searchByVector(
        tenantId: TenantId,
        scopes: Set<MemoryScope>,
        vector: List<Double>,
        topK: Int,
        threshold: Double,
    ): List<Memory> =
        memories.values
            .filter { it.tenantId == tenantId && it.scope in scopes }
            .map { it to cosineSimilarity(vector, it.embedding) }
            .filter { (_, similarity) -> similarity >= threshold }
            .sortedByDescending { (_, similarity) -> similarity }
            .take(topK)
            .map { (memory, _) -> memory }

    override fun delete(memoryId: String) {
        memories.remove(memoryId)
    }

    private fun cosineSimilarity(
        a: List<Double>,
        b: List<Double>,
    ): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        val normA = sqrt(a.sumOf { it * it })
        val normB = sqrt(b.sumOf { it * it })
        return if (normA == 0.0 || normB == 0.0) {
            0.0
        } else {
            val dot = a.indices.sumOf { a[it] * b[it] }
            dot / (normA * normB)
        }
    }
}
