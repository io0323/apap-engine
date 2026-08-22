package apap.context

import apap.domain.event.MemoryDeleted
import apap.domain.event.MemoryStored
import apap.domain.model.conversation.MemoryScope
import apap.domain.model.vo.TenantId
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import apap.testkit.inmemory.InMemoryMemoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class MemoryManagerTest {
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val otherTenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FAW")
    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val repository = InMemoryMemoryRepository()
    private val ids = InMemoryIdGenerator()
    private val events = InMemoryDomainEventPublisher()
    private val manager = MemoryManager(repository, clock, ids, events)

    @Test
    fun `store publishes MemoryStored and search finds it above the similarity threshold`() {
        val content = "likes concise answers"
        val stored = manager.store(tenantId, MemoryScope.USER, content, listOf(1.0, 0.0), 0.8, "trace-1")
        assertTrue(events.publishedEvents.filterIsInstance<MemoryStored>().any { it.memoryId == stored.memoryId })

        val found = manager.search(tenantId, setOf(MemoryScope.USER), listOf(1.0, 0.0), topK = 5, threshold = 0.75)
        assertEquals(listOf(stored.memoryId), found.map { it.memoryId })
    }

    @Test
    fun `search does not return memories from a different tenant`() {
        manager.store(tenantId, MemoryScope.USER, "belongs to tenant A", listOf(1.0, 0.0), 0.8, "trace-1")
        val found = manager.search(otherTenantId, setOf(MemoryScope.USER), listOf(1.0, 0.0), topK = 5, threshold = 0.5)
        assertTrue(found.isEmpty())
    }

    @Test
    fun `search does not return memories outside the requested scopes`() {
        manager.store(tenantId, MemoryScope.AGENT, "agent-only memory", listOf(1.0, 0.0), 0.8, "trace-1")
        val found = manager.search(tenantId, setOf(MemoryScope.USER), listOf(1.0, 0.0), topK = 5, threshold = 0.5)
        assertTrue(found.isEmpty())
    }

    @Test
    fun `delete publishes MemoryDeleted and removes the memory from search results`() {
        val stored = manager.store(tenantId, MemoryScope.TENANT, "temp", listOf(1.0, 0.0), 0.8, "trace-1")
        manager.delete(stored.memoryId, tenantId, "trace-2")

        assertTrue(events.publishedEvents.filterIsInstance<MemoryDeleted>().any { it.memoryId == stored.memoryId })
        val found = manager.search(tenantId, setOf(MemoryScope.TENANT), listOf(1.0, 0.0), topK = 5, threshold = 0.5)
        assertTrue(found.isEmpty())
    }
}
