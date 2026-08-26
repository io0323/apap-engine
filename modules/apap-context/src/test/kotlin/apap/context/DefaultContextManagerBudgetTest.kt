package apap.context

import apap.domain.event.TokenLimitExceeded
import apap.domain.model.conversation.Conversation
import apap.domain.model.conversation.ConversationStatus
import apap.domain.model.conversation.Turn
import apap.domain.model.conversation.TurnRole
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.modelcatalog.Model
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.service.execution.TokenEstimationConfig
import apap.domain.service.execution.TokenEstimationMode
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import apap.testkit.inmemory.InMemoryMemoryRepository
import apap.testkit.inmemory.InMemoryModelRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * [DefaultContextManager.build]の予算計算・圧縮・超過時の挙動を、決定的な1文字=1トークンの
 * フェイクカウンタで検証する（実HEURISTIC/EXACT計算式自体は[apap.domain.service.execution.TokenEstimationServiceTest]で
 * 別途検証済み）。
 */
class DefaultContextManagerBudgetTest {
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FAA")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FAB")
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FAC")
    private val sessionId = SessionId("01ARZ3NDEKTSV4RRFFQ69G5FAD")
    private val conversationId = ConversationId("01ARZ3NDEKTSV4RRFFQ69G5FAE")
    private val requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FAF")

    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val ids = InMemoryIdGenerator()
    private val events = InMemoryDomainEventPublisher()
    private val modelRepository = InMemoryModelRepository()
    private val memoryManager = MemoryManager(InMemoryMemoryRepository(), clock, ids, events)

    /** 1文字=1トークンの決定的カウンタ。マージン計算に影響しないよう既定はHEURISTIC。 */
    private class FixedCharTokenCounter(
        override val mode: TokenEstimationMode = TokenEstimationMode.HEURISTIC,
    ) : ContextTokenCounter {
        override fun count(parts: List<ContentPart>): TokenCount {
            val chars = parts.sumOf { part -> if (part is ContentPart.Text) part.text.length else 0 }
            return TokenCount(chars)
        }
    }

    private fun model(
        contextWindow: Int,
        maxOutputTokens: Int = 1,
    ): Model =
        Model(
            modelId = modelId,
            providerId = providerId,
            modelName = "model-a",
            version = "1",
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            regions = emptySet(),
            status = ModelStatus.ACTIVE,
            priority = 50,
        ).also { modelRepository.save(it) }

    private fun request(text: String = "hi") =
        CanonicalRequest(
            requestId = requestId,
            tenantId = tenantId,
            principal = "user-1",
            capabilityId = CapabilityId("chat"),
            input = listOf(ContentPart.Text(text)),
            timeoutBudget = Duration.ofSeconds(30),
            traceId = "trace-1",
        )

    private fun turn(
        seq: Int,
        text: String,
    ) = Turn(
        turnId = "t$seq",
        seq = seq,
        role = TurnRole.USER,
        contentParts = listOf(ContentPart.Text(text)),
        createdAt = clock.now(),
    )

    private fun conversation(turns: List<Turn>) =
        Conversation(
            conversationId = conversationId,
            sessionId = sessionId,
            tenantId = tenantId,
            status = ConversationStatus.ACTIVE,
            turns = turns,
        )

    private fun manager(
        counter: ContextTokenCounter = FixedCharTokenCounter(),
        config: TokenEstimationConfig =
            TokenEstimationConfig(exactSafetyMarginRatio = 0.0, heuristicSafetyMarginRatio = 0.0),
    ) = DefaultContextManager(
        modelRepository = modelRepository,
        memoryManager = memoryManager,
        tokenCounterFactory = { counter },
        clock = clock,
        idGenerator = ids,
        eventPublisher = events,
        estimationConfig = config,
    )

    @Test
    fun `boundary exactly at budget keeps all turns without truncation`() =
        runBlocking {
            // "hi" (2 chars) fixed input + one 8-char turn = 10 chars.
            // budget = contextWindow(11) - maxOutputTokens(1) = 10.
            model(contextWindow = 11)
            val result =
                manager().build(request("hi"), emptyList(), conversation(listOf(turn(1, "12345678"))), modelId)
            assertFalse(result.truncated)
            assertEquals(1, result.turns.size)
            assertEquals(TokenCount(10), result.estimatedTokens)
        }

    @Test
    fun `one token over budget truncates the oldest turn deterministically`() =
        runBlocking {
            model(contextWindow = 11)
            val history = listOf(turn(1, "12345678"), turn(2, "9"))
            val result = manager().build(request("hi"), emptyList(), conversation(history), modelId)
            assertTrue(result.truncated)
            // The oldest turn (seq=1) is dropped first; the newest (seq=2) is kept.
            assertEquals(listOf(2), result.turns.map { it.seq })
        }

    @Test
    fun `still over budget after dropping all history fires TokenLimitExceeded and throws`() {
        model(contextWindow = 6)
        val exception =
            assertThrows(ContextLengthExceededException::class.java) {
                runBlocking { manager().build(request("far too long for the budget"), emptyList(), null, modelId) }
            }
        assertEquals(ErrorCode.CONTEXT_LENGTH_EXCEEDED, exception.errorCode)

        val published = events.publishedEvents.filterIsInstance<TokenLimitExceeded>()
        assertEquals(1, published.size)
        assertEquals(requestId, published.single().requestId)
        assertEquals(TokenCount(5), published.single().limit)
    }

    @Test
    fun `EXACT mode uses a smaller safety margin than HEURISTIC, yielding a larger usable budget`() =
        runBlocking {
            model(contextWindow = 100, maxOutputTokens = 1)
            val config = TokenEstimationConfig(exactSafetyMarginRatio = 0.05, heuristicSafetyMarginRatio = 0.50)
            // Fixed input is 1 char; budgets are EXACT=95, HEURISTIC=50 -> 94/49 left for history.
            // 90 chars of history fits under EXACT's remaining budget but not under HEURISTIC's.
            val history = listOf(turn(1, "a".repeat(90)))

            val exactResult =
                manager(FixedCharTokenCounter(TokenEstimationMode.EXACT), config)
                    .build(request(" "), emptyList(), conversation(history), modelId)
            assertFalse(exactResult.truncated)

            val heuristicResult =
                manager(FixedCharTokenCounter(TokenEstimationMode.HEURISTIC), config)
                    .build(request(" "), emptyList(), conversation(history), modelId)
            assertTrue(heuristicResult.truncated)
        }
}
