package apap.runtime

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.mock.MockProviderAdapter
import apap.adapter.spi.AdapterChunk
import apap.adapter.spi.AdapterChunkType
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.adapter.spi.TextContentPart
import apap.adapter.spi.plugin.PluginManifest
import apap.adapter.spi.plugin.SemVerRange
import apap.domain.model.audit.AuditSearchCriteria
import apap.domain.model.conversation.Conversation
import apap.domain.model.conversation.TurnRole
import apap.domain.model.cost.PriceBook
import apap.domain.model.cost.PriceEntry
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.StreamChunkType
import apap.domain.model.modelcatalog.Model
import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.Provider
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.execution.ExecutionEngine
import apap.observability.audit.AuditConfig
import apap.observability.audit.AuditEngine
import apap.provider.AdapterRegistry
import apap.provider.PluginNotFoundException
import apap.provider.ResolvedPlugin
import apap.testkit.inmemory.InMemoryAliasRepository
import apap.testkit.inmemory.InMemoryAuditRepository
import apap.testkit.inmemory.InMemoryBudgetRepository
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryConversationRepository
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryHealthLatencyStatsRepository
import apap.testkit.inmemory.InMemoryIdGenerator
import apap.testkit.inmemory.InMemoryMemoryRepository
import apap.testkit.inmemory.InMemoryMetricsRecorder
import apap.testkit.inmemory.InMemoryModelRepository
import apap.testkit.inmemory.InMemoryPolicyRepository
import apap.testkit.inmemory.InMemoryPriceBookRepository
import apap.testkit.inmemory.InMemoryProviderRepository
import apap.testkit.inmemory.InMemoryQuotaPolicyRepository
import apap.testkit.inmemory.InMemoryQuotaSnapshotRepository
import apap.testkit.inmemory.InMemoryTenantEntitlementRepository
import apap.testkit.inmemory.InMemoryUsageRepository
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.StatusData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * 着手前レビュー item2: 「実装とテストは存在し、composerにも配線されているが実行経路から呼ばれて
 * いない」を個別対処ではなく機械的に検出するためのCapability別エンドツーエンドスモークテスト。
 * [ExecutionEngineComposer]で組み立てた実エンジンに対して実行する（adapter-mockのみ、実Providerへは
 * 接続しない）。各テストは「応答が返る」だけでなく、そのCapabilityに固有の副作用まで検証する。
 * 対象は現時点で実装済みのCapability（chat/streaming chat/embedding）。未実装のCapabilityは
 * [Disabled]でその旨を明示し、実装時にテストを有効化する運用とする。
 */
class CapabilitySmokeTest {
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FC0")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FC1")
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FC2")

    private fun priceBookRepository(vararg modelIds: ModelId): InMemoryPriceBookRepository {
        val repository = InMemoryPriceBookRepository()
        val period = Period(Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2030-01-01T00:00:00Z"))
        val entries =
            modelIds.map { id ->
                PriceEntry(id, Money(BigDecimal("1.00"), "USD"), Money(BigDecimal("1.00"), "USD"), period)
            }
        repository.save(PriceBook("book-1", entries))
        return repository
    }

    private class Harness {
        val providerRepository = InMemoryProviderRepository()
        val modelRepository = InMemoryModelRepository()
        val conversationRepository = InMemoryConversationRepository()
        val usageRepository = InMemoryUsageRepository()
        val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
        val events = InMemoryDomainEventPublisher()
        val ids = InMemoryIdGenerator()
        val metricsRecorder = InMemoryMetricsRecorder()
        val auditRepository = InMemoryAuditRepository()

        // AuditEngineのコンストラクタでeventsを購読するだけで、Capability固有のテストコードには
        // 一切登場しない横断的関心事（ADR未満: 着手前レビュー item4）。AuditEngine自体は
        // 非同期ワーカーで処理するため、記録内容を検証するテストは`awaitQuiescence()`で待ち合わせる。
        val auditEngine = AuditEngine(events, auditRepository, AuditConfig(), ids)
    }

    @Suppress("LongMethod", "LongParameterList")
    private fun buildEngine(
        harness: Harness,
        capabilityId: CapabilityId,
        adapterConfig: MockAdapterConfig,
        priceBook: InMemoryPriceBookRepository,
        tracer: Tracer = OpenTelemetry.noop().getTracer("test"),
    ): ExecutionEngine {
        harness.providerRepository.save(
            Provider(
                providerId = providerId,
                name = "provider-a",
                adapterPluginId = "plugin-a",
                spiVersion = SemVer(1, 0, 0),
                endpoints = listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                authType = "api_key",
                credentialRefs = listOf(CredentialRef("secret-ref", 1, CredentialState.ACTIVE)),
                rateLimits = RateLimits(600, 100_000, 10),
                priority = 50,
                regions = setOf(region),
                status = ProviderStatus.ACTIVE,
            ),
        )
        harness.modelRepository.save(
            Model(
                modelId = modelId,
                providerId = providerId,
                modelName = "model-a",
                version = "1.0",
                capabilities = listOf(ModelCapability(capabilityId)),
                contextWindow = 8000,
                maxOutputTokens = 1000,
                regions = setOf(region),
                status = ModelStatus.ACTIVE,
                priority = 50,
            ),
        )

        val adapter = MockProviderAdapter(adapterConfig)
        adapter.initialize(
            AdapterConfig(
                providerId,
                listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                RateLimits(600, 100_000, 10),
                setOf(region),
            ),
            object : SecretAccessor {
                override fun resolve(ref: CredentialRef): SecretValue = SecretValue("secret".toCharArray())
            },
        )
        val adapterRegistry =
            object : AdapterRegistry {
                override fun resolve(pluginId: String): ResolvedPlugin {
                    if (pluginId != "plugin-a") throw PluginNotFoundException(pluginId)
                    return ResolvedPlugin(
                        adapter,
                        PluginManifest(
                            "plugin-a",
                            SemVer(1, 0, 0),
                            SemVerRange(listOf(SemVerRange.Comparator(SemVerRange.Op.GTE, SemVer(1, 0, 0)))),
                            "apap.adapter.mock.MockProviderAdapter",
                            setOf(capabilityId),
                            setOf("api_key"),
                            "sig",
                        ),
                    )
                }
            }

        return ExecutionEngineComposer(
            harness.providerRepository,
            harness.modelRepository,
            InMemoryAliasRepository(),
            InMemoryPolicyRepository(),
            InMemoryHealthLatencyStatsRepository(),
            InMemoryQuotaSnapshotRepository(),
            InMemoryTenantEntitlementRepository(),
            InMemoryMemoryRepository(),
            harness.conversationRepository,
            priceBook,
            InMemoryBudgetRepository(),
            harness.usageRepository,
            InMemoryQuotaPolicyRepository(),
            adapterRegistry,
            harness.clock,
            harness.ids,
            harness.events,
            harness.events,
            tracer,
            harness.metricsRecorder,
        ).build()
    }

    @Test
    fun `chat capability returns a response and records both turns`() =
        runBlocking {
            val capabilityId = CapabilityId("chat")
            val harness = Harness()
            val adapterConfig = MockAdapterConfig(supportedCapabilities = setOf(capabilityId))
            val engine = buildEngine(harness, capabilityId, adapterConfig, priceBookRepository(modelId))
            val conversationId = ConversationId("01ARZ3NDEKTSV4RRFFQ69G5FC3")
            harness.conversationRepository.save(
                Conversation(conversationId, SessionId("01ARZ3NDEKTSV4RRFFQ69G5FC4"), tenantId),
            )

            val request =
                CanonicalRequest(
                    requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FC5"),
                    tenantId = tenantId,
                    principal = "user-1",
                    capabilityId = capabilityId,
                    input = listOf(ContentPart.Text("hello")),
                    conversationId = conversationId,
                    timeoutBudget = Duration.ofSeconds(30),
                    traceId = "trace-1",
                )

            val response = engine.execute(request)
            assertTrue(response.output.isNotEmpty())

            val turns = harness.conversationRepository.findTurns(conversationId, tenantId, 1..Int.MAX_VALUE)
            assertEquals(2, turns.size)
            assertEquals(listOf(TurnRole.USER, TurnRole.ASSISTANT), turns.map { it.role })
        }

    /**
     * 着手前レビュー item4: Capability固有の副作用（応答・Turn記録等）だけでなく、Capabilityに
     * 紐づかない横断的関心事（Metrics/Audit/Domain Event配信）が実際に動作していることを検証する。
     * `MetricsEngine`が本番配線から一度も構築されていなかった件は、Capability別スモークテストが
     * Capability固有の副作用しか検証していなかったために見逃されていた——この1テストがその空白を埋める。
     */
    @Test
    fun `chat capability also records metrics, an audit trail, and delivers events to subscribers`() =
        runBlocking {
            val capabilityId = CapabilityId("chat")
            val harness = Harness()
            val adapterConfig = MockAdapterConfig(supportedCapabilities = setOf(capabilityId))
            val engine = buildEngine(harness, capabilityId, adapterConfig, priceBookRepository(modelId))

            val independentlyObservedEvents = mutableListOf<apap.domain.event.DomainEvent>()
            harness.events.subscribe { independentlyObservedEvents.add(it) }

            val request =
                CanonicalRequest(
                    requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FE1"),
                    tenantId = tenantId,
                    principal = "user-1",
                    capabilityId = capabilityId,
                    input = listOf(ContentPart.Text("hello")),
                    timeoutBudget = Duration.ofSeconds(30),
                    traceId = "trace-1",
                )

            engine.execute(request)
            harness.auditEngine.awaitQuiescence()

            // Metrics: apap_requests_total / apap_request_duration_seconds / apap_tokens_total /
            // apap_cost_total（RequestCompleted経由）と apap_overhead_duration_seconds
            // （PhaseTimings直接呼出）の両方が、ExecutionEngineComposerの実配線経由で記録されること。
            assertTrue(harness.metricsRecorder.requests.any { it.capabilityId == capabilityId })
            assertTrue(harness.metricsRecorder.requestDurations.isNotEmpty())
            assertTrue(harness.metricsRecorder.tokens.isNotEmpty())
            assertTrue(harness.metricsRecorder.costs.isNotEmpty())
            assertTrue(
                harness.metricsRecorder.overheadDurations
                    .map { it.phase }
                    .containsAll(listOf("prompt", "routing", "mapping")),
            )

            // Audit: RequestReceived〜RequestCompletedがAuditEngineで相関され、追記専用ストアに残ること。
            val auditRecords = harness.auditRepository.search(AuditSearchCriteria(requestId = request.requestId))
            assertEquals(1, auditRecords.size)
            assertEquals(tenantId, auditRecords.single().tenantId)

            // Domain Event配信: publisherの内部ログだけでなく、別途登録した独立の購読者にも届くこと。
            assertTrue(independentlyObservedEvents.any { it is apap.domain.event.RequestCompleted })
        }

    /**
     * 02_システム仕様.md 2.19 Span構成（gateway → prompt → routing → attempt[n] → mapping）が
     * composer配線を通じて実際にエクスポートされることを、実OpenTelemetry SDK
     * （[InMemorySpanExporter]、テスト専用。本体はAPIのみに依存、CLAUDE.md不変条件6）で検証する。
     */
    @Test
    fun `chat capability exports the expected span hierarchy via a real Tracer`() =
        runBlocking {
            val capabilityId = CapabilityId("chat")
            val harness = Harness()
            val adapterConfig = MockAdapterConfig(supportedCapabilities = setOf(capabilityId))
            val spanExporter = InMemorySpanExporter.create()
            val tracerProvider =
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)).build()
            val tracer =
                OpenTelemetrySdk
                    .builder()
                    .setTracerProvider(tracerProvider)
                    .build()
                    .getTracer("test")
            val engine = buildEngine(harness, capabilityId, adapterConfig, priceBookRepository(modelId), tracer)

            val request =
                CanonicalRequest(
                    requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FE0"),
                    tenantId = tenantId,
                    principal = "user-1",
                    capabilityId = capabilityId,
                    input = listOf(ContentPart.Text("hello")),
                    timeoutBudget = Duration.ofSeconds(30),
                    traceId = "trace-1",
                )
            engine.execute(request)

            val spans = spanExporter.finishedSpanItems
            val spanNames = spans.map { it.name }.toSet()
            val expectedSpanNames =
                setOf(
                    "apap.execute",
                    "prompt",
                    "cache-lookup",
                    "routing",
                    "context",
                    "token-estimate",
                    "execution",
                    "mapping",
                    "attempt[1]",
                )
            assertEquals(expectedSpanNames, spanNames)
            assertTrue(spans.all { it.status.statusCode == StatusData.ok().statusCode })

            val rootSpan = spans.single { it.name == "apap.execute" }
            val executionSpan = spans.single { it.name == "execution" }
            val attemptSpan = spans.single { it.name == "attempt[1]" }
            assertEquals(rootSpan.spanId, executionSpan.parentSpanId)
            assertEquals(executionSpan.spanId, attemptSpan.parentSpanId)
            assertEquals(providerId.value, attemptSpan.attributes.get(AttributeKey.stringKey("provider")))
        }

    @Suppress("LongMethod")
    @Test
    fun `streaming chat capability returns chunks, confirms usage, and records both turns`() =
        runBlocking {
            val capabilityId = CapabilityId("chat")
            val harness = Harness()
            val streamChunks =
                listOf(
                    AdapterChunk(type = AdapterChunkType.MESSAGE_START, index = 0),
                    AdapterChunk(type = AdapterChunkType.CONTENT_DELTA, index = 1, delta = TextContentPart("hello")),
                    AdapterChunk(
                        type = AdapterChunkType.USAGE,
                        index = 2,
                        usage = Usage.of(TokenCount(5), TokenCount(3)),
                    ),
                    AdapterChunk(type = AdapterChunkType.MESSAGE_END, index = 3),
                )
            val adapterConfig =
                MockAdapterConfig(supportedCapabilities = setOf(capabilityId), streamChunks = streamChunks)
            val engine = buildEngine(harness, capabilityId, adapterConfig, priceBookRepository(modelId))
            val conversationId = ConversationId("01ARZ3NDEKTSV4RRFFQ69G5FC6")
            harness.conversationRepository.save(
                Conversation(conversationId, SessionId("01ARZ3NDEKTSV4RRFFQ69G5FC7"), tenantId),
            )

            val request =
                CanonicalRequest(
                    requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FC8"),
                    tenantId = tenantId,
                    principal = "user-1",
                    capabilityId = capabilityId,
                    input = listOf(ContentPart.Text("hello")),
                    conversationId = conversationId,
                    timeoutBudget = Duration.ofSeconds(30),
                    traceId = "trace-1",
                )

            val chunks = engine.executeStream(request).toList()
            assertTrue(chunks.any { it.type == StreamChunkType.MESSAGE_END })
            assertTrue(chunks.none { it.type == StreamChunkType.ERROR })

            val period = Period(Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2030-01-01T00:00:00Z"))
            val aggregate = harness.usageRepository.aggregate(tenantId, period, emptyList())
            assertEquals(1, aggregate.single().requestCount)
            assertEquals(TokenCount(5), aggregate.single().totalUsage.inputTokens)

            val turns = harness.conversationRepository.findTurns(conversationId, tenantId, 1..Int.MAX_VALUE)
            assertEquals(2, turns.size)
            assertEquals(listOf(TurnRole.USER, TurnRole.ASSISTANT), turns.map { it.role })
        }

    @Test
    fun `embedding capability is deterministic and its response gets cached`() =
        runBlocking {
            val capabilityId = CapabilityId("embedding")
            val harness = Harness()
            val adapterConfig = MockAdapterConfig(supportedCapabilities = setOf(capabilityId))
            val engine = buildEngine(harness, capabilityId, adapterConfig, priceBookRepository(modelId))

            fun request(requestId: String) =
                CanonicalRequest(
                    requestId = RequestId(requestId),
                    tenantId = tenantId,
                    principal = "user-1",
                    capabilityId = capabilityId,
                    input = listOf(ContentPart.Text("embed me")),
                    timeoutBudget = Duration.ofSeconds(30),
                    traceId = "trace-1",
                )

            val first = engine.execute(request("01ARZ3NDEKTSV4RRFFQ69G5FC9"))
            assertFalse(first.cached)

            val second = engine.execute(request("01ARZ3NDEKTSV4RRFFQ69G5FD0"))
            assertTrue(second.cached)
        }

    /**
     * FR-CAP-016 Batch Processingは`ExecutionEngineComposer`に配線されておらず（requirements-matrix.md
     * 参照）、実行フローとして到達不能。個別対処ではなく機械的に検出する、というタスク要求どおり、
     * 「未実装であること」をこのskipで明示する。実装時にこのテストを有効化すること。
     */
    @Disabled("Batch capability (FR-CAP-016) is not wired into ExecutionEngineComposer yet. Enable once implemented.")
    @Test
    fun `batch capability`() {
        error("not implemented")
    }
}
