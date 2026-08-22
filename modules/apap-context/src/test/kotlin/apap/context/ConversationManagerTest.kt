package apap.context

import apap.domain.event.ConversationDeleted
import apap.domain.model.conversation.TurnRole
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryConversationRepository
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 04_ドメイン設計.md 4.3.4: Turn seqの欠番なし単調増加（並行追記時も）。 */
class ConversationManagerTest {
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val sessionId = SessionId("01ARZ3NDEKTSV4RRFFQ69G5FAW")
    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val repository = InMemoryConversationRepository()
    private val ids = InMemoryIdGenerator()
    private val events = InMemoryDomainEventPublisher()
    private val manager = ConversationManager(repository, clock, ids, events)

    @Test
    fun `appendTurn assigns sequential seq starting at 1`() {
        val conversation = manager.start(sessionId, tenantId)
        val first = manager.appendTurn(conversation.conversationId, TurnRole.USER, listOf(ContentPart.Text("hi")))
        val second =
            manager.appendTurn(conversation.conversationId, TurnRole.ASSISTANT, listOf(ContentPart.Text("hello")))
        assertEquals(1, first.seq)
        assertEquals(2, second.seq)
    }

    @Test
    fun `concurrent appendTurn calls never produce gaps or duplicate seq values`() {
        // A generous retry budget avoids flakiness from unlucky scheduling under real thread
        // contention (32 threads racing to append to the same Conversation) while still exercising
        // the actual optimistic-retry path, not just a single lucky winner per round.
        val concurrentManager = ConversationManager(repository, clock, ids, events, maxSeqRetries = 200)
        val conversation = concurrentManager.start(sessionId, tenantId)
        val threadCount = 32
        val pool = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        try {
            val futures =
                (1..threadCount).map { i ->
                    pool.submit {
                        ready.countDown()
                        start.await()
                        val content = listOf(ContentPart.Text("msg-$i"))
                        concurrentManager.appendTurn(conversation.conversationId, TurnRole.USER, content)
                    }
                }
            ready.await(5, TimeUnit.SECONDS)
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }

        val seqs = concurrentManager.history(conversation.conversationId).map { it.seq }
        assertEquals((1..threadCount).toList(), seqs.sorted())
    }

    @Test
    fun `appendTurn throws when the conversation does not exist`() {
        val unknownId = ConversationId("01ARZ3NDEKTSV4RRFFQ69G5FAX")
        assertThrows(ConversationNotFoundException::class.java) {
            manager.appendTurn(unknownId, TurnRole.USER, listOf(ContentPart.Text("hi")))
        }
    }

    @Test
    fun `delete publishes ConversationDeleted`() {
        val conversation = manager.start(sessionId, tenantId)
        manager.delete(conversation.conversationId, "trace-1")
        val deletedEvents = events.publishedEvents.filterIsInstance<ConversationDeleted>()
        assertTrue(deletedEvents.any { it.conversationId == conversation.conversationId })
    }
}
