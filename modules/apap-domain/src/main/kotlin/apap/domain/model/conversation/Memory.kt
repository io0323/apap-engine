package apap.domain.model.conversation

import apap.domain.model.vo.TenantId
import java.time.Instant

enum class MemoryScope { TENANT, USER, AGENT }

/**
 * 04_ドメイン設計.md 4.3.5 Memory Aggregate（Root）: scopeを跨ぐ参照不可。
 * この不変条件は「同一Memoryインスタンスがscopeを跨いで存在する」ことを禁じるものではなく、
 * 検索・参照時にscopeでフィルタする必要があることを指す。単一インスタンスの範囲では検証できないため、
 * scopeを跨いだ参照を防ぐのはMemoryRepository.searchByVectorの呼び出し側（scope引数必須）が担う。
 */
data class Memory(
    val memoryId: String,
    val tenantId: TenantId,
    val scope: MemoryScope,
    val content: String,
    val embedding: List<Double>,
    val importance: Double,
    val ttlAt: Instant? = null,
    val lastAccessedAt: Instant,
) {
    init {
        require(content.isNotBlank()) { "content must not be blank" }
        require(embedding.isNotEmpty()) { "embedding must not be empty" }
        require(importance in 0.0..1.0) { "importance must be within 0.0..1.0: $importance" }
    }

    fun touch(at: Instant): Memory = copy(lastAccessedAt = at)
}
