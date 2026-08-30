package apap.infrastructure.persistence.inmemory

import apap.domain.model.conversation.Memory
import apap.domain.model.conversation.MemoryScope
import apap.domain.model.vo.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryMemoryRepositoryTest {
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val otherTenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun memory(
        id: String,
        tenant: TenantId,
        scope: MemoryScope,
        embedding: List<Double>,
    ) = Memory(
        memoryId = id,
        tenantId = tenant,
        scope = scope,
        content = "content-$id",
        embedding = embedding,
        importance = 0.5,
        lastAccessedAt = now,
    )

    @Test
    fun `searchByVector ranks by cosine similarity and respects the threshold and tenant-scope boundary`() {
        val repo = InMemoryMemoryRepository()
        repo.store(memory("exact", tenantId, MemoryScope.TENANT, listOf(1.0, 0.0)))
        repo.store(memory("orthogonal", tenantId, MemoryScope.TENANT, listOf(0.0, 1.0)))
        repo.store(memory("other-tenant", otherTenantId, MemoryScope.TENANT, listOf(1.0, 0.0)))
        repo.store(memory("other-scope", tenantId, MemoryScope.AGENT, listOf(1.0, 0.0)))

        val results =
            repo.searchByVector(
                tenantId = tenantId,
                scopes = setOf(MemoryScope.TENANT),
                vector = listOf(1.0, 0.0),
                topK = 10,
                threshold = 0.5,
            )

        assertEquals(listOf("exact"), results.map { it.memoryId })
    }

    @Test
    fun `delete removes a stored memory`() {
        val repo = InMemoryMemoryRepository()
        repo.store(memory("m1", tenantId, MemoryScope.TENANT, listOf(1.0, 0.0)))

        repo.delete("m1")

        assertEquals(0, repo.searchByVector(tenantId, setOf(MemoryScope.TENANT), listOf(1.0, 0.0), 10, 0.0).size)
    }
}
