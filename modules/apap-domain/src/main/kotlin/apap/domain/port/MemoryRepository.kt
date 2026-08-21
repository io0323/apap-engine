package apap.domain.port

import apap.domain.model.conversation.Memory

interface MemoryRepository {
    fun store(memory: Memory)

    /** 02_システム仕様.md 2.17: 類似検索（top-k、類似度閾値）。 */
    fun searchByVector(
        vector: List<Double>,
        topK: Int,
        threshold: Double,
    ): List<Memory>

    fun delete(memoryId: String)
}
