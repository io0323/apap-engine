package apap.testkit.inmemory

import apap.domain.model.conversation.Memory
import apap.domain.port.MemoryRepository
import kotlin.math.sqrt

/** 02_システム仕様.md 2.17: top-k類似検索（類似度=コサイン類似度、閾値以上のみ、類似度降順）。 */
class InMemoryMemoryRepository : MemoryRepository {
    private val memories = mutableMapOf<String, Memory>()

    override fun store(memory: Memory) {
        memories[memory.memoryId] = memory
    }

    override fun searchByVector(
        vector: List<Double>,
        topK: Int,
        threshold: Double,
    ): List<Memory> =
        memories.values
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
