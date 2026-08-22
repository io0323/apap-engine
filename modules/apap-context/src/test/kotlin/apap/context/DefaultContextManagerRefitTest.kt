package apap.context

import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.modelcatalog.Model
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TokenCount
import apap.domain.service.execution.TokenEstimationConfig
import apap.domain.service.execution.TokenEstimationMode
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import apap.testkit.inmemory.InMemoryMemoryRepository
import apap.testkit.inmemory.InMemoryModelRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** [DefaultContextManager.refit]: 縮小Modelへ切り替えても予算を超えないことの検証（必須テスト）。 */
class DefaultContextManagerRefitTest {
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FAB")
    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val ids = InMemoryIdGenerator()
    private val events = InMemoryDomainEventPublisher()
    private val modelRepository = InMemoryModelRepository()
    private val memoryManager = MemoryManager(InMemoryMemoryRepository(), clock, ids, events)
    private val config = TokenEstimationConfig(exactSafetyMarginRatio = 0.0, heuristicSafetyMarginRatio = 0.0)

    private class FixedCharTokenCounter : ContextTokenCounter {
        override val mode = TokenEstimationMode.HEURISTIC

        override fun count(parts: List<ContentPart>): TokenCount {
            val chars = parts.sumOf { part -> if (part is ContentPart.Text) part.text.length else 0 }
            return TokenCount(chars)
        }
    }

    private fun model(
        id: ModelId,
        contextWindow: Int,
    ): Model =
        Model(
            modelId = id,
            providerId = providerId,
            modelName = "model-$id",
            version = "1",
            contextWindow = contextWindow,
            maxOutputTokens = 1,
            regions = emptySet(),
            status = ModelStatus.ACTIVE,
            priority = 50,
        ).also { modelRepository.save(it) }

    private fun manager() =
        DefaultContextManager(
            modelRepository = modelRepository,
            memoryManager = memoryManager,
            tokenCounterFactory = { FixedCharTokenCounter() },
            clock = clock,
            idGenerator = ids,
            eventPublisher = events,
            estimationConfig = config,
        )

    @Test
    fun `refit to a smaller-context-window model trims from the oldest content and never exceeds the new budget`() {
        val bigModel = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
        val smallModel = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA2")
        model(bigModel, contextWindow = 101) // budget = 100
        model(smallModel, contextWindow = 21) // budget = 20

        val prompt =
            ProcessedPrompt(
                input =
                    listOf(
                        ContentPart.Text("a".repeat(40)),
                        ContentPart.Text("b".repeat(30)),
                        ContentPart.Text("c".repeat(15)),
                    ),
            )
        // Fits the big model's budget (85 chars <= 100) without trimming.
        val refitToBig = manager().refit(prompt, bigModel)
        assertEquals(prompt.input, refitToBig.input)

        // Does not fit the small model's budget (85 > 20); the oldest parts are trimmed from the front
        // until what remains fits, and the remaining content never exceeds the new budget.
        val refitToSmall = manager().refit(prompt, smallModel)
        val remainingChars = refitToSmall.input.sumOf { (it as ContentPart.Text).text.length }
        assertTrue(remainingChars <= 20)
        // The newest part ("c"x15) must be the last one kept.
        assertEquals("c".repeat(15), (refitToSmall.input.last() as ContentPart.Text).text)
    }

    @Test
    fun `throws when even the single newest part does not fit the new budget`() {
        val tinyModel = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA3")
        model(tinyModel, contextWindow = 6) // budget = 5
        val prompt = ProcessedPrompt(input = listOf(ContentPart.Text("this single part is too long")))

        assertThrows(ContextLengthExceededException::class.java) {
            manager().refit(prompt, tinyModel)
        }
    }
}
