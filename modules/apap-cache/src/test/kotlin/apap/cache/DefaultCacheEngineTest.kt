package apap.cache

import apap.domain.event.AliasChanged
import apap.domain.event.CacheHit
import apap.domain.event.CacheStored
import apap.domain.event.CacheType
import apap.domain.event.EventMetadata
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.CanonicalResponse
import apap.domain.model.execution.GenerationParams
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.modelcatalog.AliasTarget
import apap.domain.model.modelcatalog.ModelAlias
import apap.domain.model.vo.AliasId
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.Cost
import apap.domain.model.vo.FinishReason
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.testkit.inmemory.InMemoryAliasRepository
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** タスク要件: Request/Response Cache、Alias切替時の一括無効化、temperature除外、TTL失効。 */
class DefaultCacheEngineTest {
    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val aliasRepository = InMemoryAliasRepository()
    private val events = InMemoryDomainEventPublisher()
    private val ids = InMemoryIdGenerator()
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA2")
    private val embeddingCapability = CapabilityId("embedding")
    private val chatCapability = CapabilityId("chat_completion")

    private fun engine(config: CacheConfig = CacheConfig()): DefaultCacheEngine<CanonicalResponse> =
        DefaultCacheEngine(
            cacheStore = InMemoryCacheStore(clock),
            cacheCodec = PassthroughCacheCodec(),
            cacheKeyStrategy = NormalizedJsonCacheKeyStrategy(),
            cacheabilityPolicy = DefaultCacheabilityPolicy(),
            config = config,
            aliasRepository = aliasRepository,
            clock = clock,
            eventPublisher = events,
            idGenerator = ids,
        )

    private fun request(
        capabilityId: CapabilityId = embeddingCapability,
        temperature: Double? = null,
        modelAlias: String? = null,
        idempotencyKey: String? = null,
        text: String = "hello world",
    ): CanonicalRequest =
        CanonicalRequest(
            requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FA3"),
            tenantId = tenantId,
            principal = "user-1",
            capabilityId = capabilityId,
            modelAlias = modelAlias,
            input = listOf(ContentPart.Text(text)),
            params = GenerationParams(temperature = temperature),
            idempotencyKey = idempotencyKey,
            timeoutBudget = Duration.ofSeconds(30),
            traceId = "trace-1",
        )

    private val prompt = ProcessedPrompt(input = listOf(ContentPart.Text("hello world")))

    private fun response(id: String = "resp-1"): CanonicalResponse =
        CanonicalResponse(
            responseId = id,
            requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FA3"),
            output = listOf(ContentPart.Text("hi")),
            finishReason = FinishReason.COMPLETED,
            usage = Usage.of(TokenCount(1), TokenCount(1)),
            cost = Cost(Money.zero("USD")),
            resolvedProvider = providerId,
            resolvedModel = modelId,
        )

    @Test
    fun `lookup misses when nothing has been stored`() {
        val cache = engine()
        assertNull(cache.lookup(request(), prompt))
    }

    @Test
    fun `deterministic request is cacheable and comes back with cached metadata`() {
        val cache = engine()
        val req = request(temperature = 0.0)
        cache.store(req, prompt, response())

        val hit = cache.lookup(req, prompt)
        assertNotNull(hit)
        assertTrue(hit!!.cached)
        assertEquals(clock.now().toString(), hit.metadata["cached_at"])
    }

    @Test
    fun `temperature above zero is excluded from the response cache`() {
        val cache = engine()
        val req = request(capabilityId = chatCapability, temperature = 0.7)
        cache.store(req, prompt, response())
        assertNull(cache.lookup(req, prompt))
    }

    @Test
    fun `null temperature is treated as non-deterministic and excluded`() {
        val cache = engine()
        val req = request(capabilityId = chatCapability, temperature = null)
        cache.store(req, prompt, response())
        assertNull(cache.lookup(req, prompt))
    }

    @Test
    fun `idempotency key is cached regardless of temperature`() {
        val cache = engine()
        val req = request(capabilityId = chatCapability, temperature = 0.9, idempotencyKey = "idem-1")
        cache.store(req, prompt, response())
        assertNotNull(cache.lookup(req, prompt))
    }

    @Test
    fun `switching an alias invalidates only that alias's cached entries`() {
        val cache = engine()
        val aliasA = ModelAlias(AliasId("01ARZ3NDEKTSV4RRFFQ69G5FA4"), "alias-a", listOf(AliasTarget(modelId, 100)))
        val aliasB = ModelAlias(AliasId("01ARZ3NDEKTSV4RRFFQ69G5FA5"), "alias-b", listOf(AliasTarget(modelId, 100)))
        aliasRepository.save(tenantId, aliasA)
        aliasRepository.save(tenantId, aliasB)

        val reqA = request(temperature = 0.0, modelAlias = "alias-a", text = "for alias a")
        val reqB = request(temperature = 0.0, modelAlias = "alias-b", text = "for alias b")
        cache.store(reqA, prompt, response("resp-a"))
        cache.store(reqB, prompt, response("resp-b"))

        cache.apply(
            AliasChanged(
                meta = eventMetadata(),
                aliasId = aliasA.aliasId.value,
                name = aliasA.name,
                oldTargets = emptyList(),
                newTargets = emptyList(),
            ),
        )

        assertNull(cache.lookup(reqA, prompt))
        assertNotNull(cache.lookup(reqB, prompt))
    }

    @Test
    fun `entries expire after the configured response cache ttl`() {
        val cache = engine(CacheConfig(responseCacheTtl = Duration.ofMinutes(30)))
        val req = request(temperature = 0.0)
        cache.store(req, prompt, response())
        assertNotNull(cache.lookup(req, prompt))

        clock.advanceBy(30 * 60 + 1)
        assertNull(cache.lookup(req, prompt))
    }

    /**
     * DefaultCacheEngine/CacheStoreの型パラメータ[E]がCanonicalResponse以外でも動くこと
     * （分散KVS実装、P8想定、が`CacheStore<ByteArray>`+`CacheCodec<CanonicalResponse, ByteArray>`を
     * 差し込めるSPI seamであることの直接的な証跡）。
     */
    @Test
    fun `DefaultCacheEngine works through a non-identity CacheCodec (E = String)`() {
        val cache =
            DefaultCacheEngine(
                cacheStore = InMemoryCacheStore<String>(clock),
                cacheCodec = ResponseIdOnlyCodec(),
                cacheKeyStrategy = NormalizedJsonCacheKeyStrategy(),
                cacheabilityPolicy = DefaultCacheabilityPolicy(),
                config = CacheConfig(),
                aliasRepository = aliasRepository,
                clock = clock,
                eventPublisher = events,
                idGenerator = ids,
            )
        val req = request(temperature = 0.0)
        cache.store(req, prompt, response("resp-xyz"))

        val hit = cache.lookup(req, prompt)
        assertEquals("resp-xyz", hit?.responseId)
    }

    @Test
    fun `store publishes CacheStored and lookup publishes CacheHit with the correct cache type`() {
        val cache = engine()
        val idempotentReq = request(capabilityId = chatCapability, temperature = 0.9, idempotencyKey = "idem-1")
        cache.store(idempotentReq, prompt, response())
        assertEquals(1, events.publishedEvents.size)
        assertEquals(CacheType.REQUEST, (events.publishedEvents.single() as CacheStored).cacheType)

        cache.lookup(idempotentReq, prompt)
        assertEquals(2, events.publishedEvents.size)
        assertEquals(CacheType.REQUEST, (events.publishedEvents[1] as CacheHit).cacheType)

        val deterministicReq = request(temperature = 0.0, text = "distinct")
        cache.store(deterministicReq, prompt, response())
        assertEquals(3, events.publishedEvents.size)
        assertEquals(CacheType.RESPONSE, (events.publishedEvents[2] as CacheStored).cacheType)

        cache.lookup(deterministicReq, prompt)
        assertEquals(4, events.publishedEvents.size)
        assertEquals(CacheType.RESPONSE, (events.publishedEvents[3] as CacheHit).cacheType)
    }

    private fun eventMetadata(): EventMetadata =
        EventMetadata(
            eventId = "01ARZ3NDEKTSV4RRFFQ69G5FA6",
            occurredAt = clock.now(),
            traceId = "trace-1",
            tenantId = tenantId,
            aggregateId = "01ARZ3NDEKTSV4RRFFQ69G5FA4",
            version = 1,
        )

    /** テスト用の最小限のCodec: 直列化表現[String]としてresponseIdのみを保持する。 */
    private inner class ResponseIdOnlyCodec : CacheCodec<CanonicalResponse, String> {
        override fun encode(value: CanonicalResponse): String = value.responseId

        override fun decode(encoded: String): CanonicalResponse = response(encoded)
    }
}
