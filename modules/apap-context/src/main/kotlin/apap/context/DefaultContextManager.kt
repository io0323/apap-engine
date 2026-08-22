package apap.context

import apap.domain.event.EventMetadata
import apap.domain.event.TokenLimitExceeded
import apap.domain.model.conversation.Conversation
import apap.domain.model.conversation.MemoryScope
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TokenCount
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import apap.domain.port.ModelRepository
import apap.domain.service.conversation.AssembledContext
import apap.domain.service.conversation.ContextAssemblyService
import apap.domain.service.execution.TokenEstimationConfig
import apap.domain.service.execution.TokenEstimationService

class ModelNotFoundException(
    modelId: ModelId,
) : NoSuchElementException("Model not found: $modelId")

/**
 * [ContextManager]の実装。02_システム仕様.md 2.16の合成手順（System Prompt→Memory注入→履歴→
 * 今回入力、`contextWindow - maxOutputTokens - 安全マージン`に収める）を、既存の
 * [ContextAssemblyService]/[TokenEstimationService]（ADR-0009）を再利用して実装する。
 *
 * [tokenCounterFactory]が`(ModelId) -> ContextTokenCounter`なのは、[build]/[refit]双方が
 * `modelId`を呼出時引数で受け取り（コンストラクタ時点では定まらない）、
 * [ContextTokenCounter]がmodel別（文字/トークン比）のため。
 */
@Suppress("LongParameterList")
class DefaultContextManager(
    private val modelRepository: ModelRepository,
    private val memoryManager: MemoryManager,
    private val tokenCounterFactory: (ModelId) -> ContextTokenCounter,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val eventPublisher: DomainEventPublisher,
    private val compactionStrategy: CompactionStrategy = TruncateOldestCompactionStrategy(),
    private val queryEmbedder: QueryEmbedder = NoOpQueryEmbedder(optedIn = true),
    private val estimationConfig: TokenEstimationConfig = TokenEstimationConfig(),
    private val memoryScopes: Set<MemoryScope> = MemoryScope.entries.toSet(),
    private val memoryTopK: Int = DEFAULT_MEMORY_TOP_K,
    private val memorySimilarityThreshold: Double = DEFAULT_MEMORY_SIMILARITY_THRESHOLD,
) : ContextManager {
    override fun build(
        request: CanonicalRequest,
        systemPrompt: List<ContentPart>,
        conversation: Conversation?,
        modelId: ModelId,
    ): AssembledContext {
        val model = modelRepository.findById(modelId) ?: throw ModelNotFoundException(modelId)
        val counter = tokenCounterFactory(modelId)
        val margin = TokenEstimationService.safetyMarginFor(counter.mode, estimationConfig)
        val budget = ContextAssemblyService.computeBudget(model.contextWindow, model.maxOutputTokens, margin)

        val memoryInjection = resolveMemoryInjection(request)
        val fixedTokens = counter.count(systemPrompt) + counter.count(memoryInjection) + counter.count(request.input)
        if (fixedTokens.value > budget) {
            publishTokenLimitExceeded(request, TokenCount(budget), fixedTokens)
            throw ContextLengthExceededException(TokenCount(budget), fixedTokens)
        }

        val history = conversation?.turns.orEmpty()
        val compaction = compactionStrategy.compact(history, budget - fixedTokens.value, counter::count)
        val historyTokens = counter.count(compaction.turns.flatMap { it.contentParts })

        return AssembledContext(
            systemPrompt = systemPrompt,
            memoryInjection = memoryInjection,
            turns = compaction.turns,
            currentInput = request.input,
            estimatedTokens = fixedTokens + historyTokens,
            truncated = compaction.truncated,
        )
    }

    /**
     * Fallback時に次候補[modelId]のcontext window制約へ再圧縮する（[apap.execution.fallback.FallbackEngine]が
     * 呼び出す）。`requestId`を引数に持たないため[TokenLimitExceeded]は発火しない
     * （[ContextLengthExceededException]は送出する。KDoc根拠、要件充足に影響しないためADR化せず）。
     */
    override fun refit(
        prompt: ProcessedPrompt,
        modelId: ModelId,
    ): ProcessedPrompt {
        val model = modelRepository.findById(modelId) ?: throw ModelNotFoundException(modelId)
        val counter = tokenCounterFactory(modelId)
        val margin = TokenEstimationService.safetyMarginFor(counter.mode, estimationConfig)
        val budget = ContextAssemblyService.computeBudget(model.contextWindow, model.maxOutputTokens, margin)

        var remaining = prompt.input
        while (remaining.size > 1 && counter.count(remaining).value > budget) {
            remaining = remaining.drop(1)
        }
        val finalTokens = counter.count(remaining)
        if (finalTokens.value > budget) {
            throw ContextLengthExceededException(TokenCount(budget), finalTokens)
        }
        return ProcessedPrompt(input = remaining, estimatedTokens = finalTokens)
    }

    private fun resolveMemoryInjection(request: CanonicalRequest): List<ContentPart> {
        val queryVector = queryEmbedder.embed(request.input)
        if (queryVector.isEmpty()) return emptyList()
        return memoryManager
            .search(request.tenantId, memoryScopes, queryVector, memoryTopK, memorySimilarityThreshold)
            .map { ContentPart.Text(it.content) }
    }

    private fun publishTokenLimitExceeded(
        request: CanonicalRequest,
        limit: TokenCount,
        actual: TokenCount,
    ) {
        eventPublisher.publish(
            TokenLimitExceeded(
                EventMetadata(
                    eventId = idGenerator.newId(),
                    occurredAt = clock.now(),
                    traceId = request.traceId,
                    tenantId = request.tenantId,
                    aggregateId = request.requestId.value,
                    version = 0,
                ),
                request.requestId,
                limit,
                actual,
            ),
        )
    }

    private companion object {
        const val DEFAULT_MEMORY_TOP_K = 5
        const val DEFAULT_MEMORY_SIMILARITY_THRESHOLD = 0.75
    }
}
