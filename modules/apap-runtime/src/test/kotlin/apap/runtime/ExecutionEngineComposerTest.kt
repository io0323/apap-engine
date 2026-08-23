package apap.runtime

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.mock.MockProviderAdapter
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.AdapterResponse
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.adapter.spi.plugin.PluginManifest
import apap.adapter.spi.plugin.SemVerRange
import apap.domain.event.RequestCompleted
import apap.domain.model.conversation.Conversation
import apap.domain.model.conversation.Turn
import apap.domain.model.conversation.TurnRole
import apap.domain.model.execution.CanonicalRequest
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
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import apap.execution.ExecutionFailedException
import apap.provider.AdapterRegistry
import apap.provider.PluginNotFoundException
import apap.provider.ResolvedPlugin
import apap.testkit.inmemory.InMemoryAliasRepository
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryConversationRepository
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryHealthLatencyStatsRepository
import apap.testkit.inmemory.InMemoryIdGenerator
import apap.testkit.inmemory.InMemoryMemoryRepository
import apap.testkit.inmemory.InMemoryModelRepository
import apap.testkit.inmemory.InMemoryPolicyRepository
import apap.testkit.inmemory.InMemoryProviderRepository
import apap.testkit.inmemory.InMemoryQuotaSnapshotRepository
import apap.testkit.inmemory.InMemoryTenantEntitlementRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * apap-runtimeコンポジションルート（[ExecutionEngineComposer]）の配線が実際に組み上がり、
 * 02_システム仕様.md 2.8のRequest Flowを最小構成（adapter-mock、実Providerへは接続しない）で
 * 一気通貫に実行できることを確認するスモークテスト。
 */
class ExecutionEngineComposerTest {
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    private val capabilityId = CapabilityId("chat")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA2")
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA3")
    private val requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FA4")

    @Suppress("LongMethod")
    @Test
    fun `wired ExecutionEngine completes a chat request end-to-end`() =
        runBlocking {
            val providerRepository = InMemoryProviderRepository()
            val modelRepository = InMemoryModelRepository()
            val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
            val ids = InMemoryIdGenerator()
            val events = InMemoryDomainEventPublisher()

            providerRepository.save(
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
            modelRepository.save(
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

            val adapter = MockProviderAdapter(MockAdapterConfig(supportedCapabilities = setOf(capabilityId)))
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

            val engine =
                ExecutionEngineComposer(
                    providerRepository,
                    modelRepository,
                    InMemoryAliasRepository(),
                    InMemoryPolicyRepository(),
                    InMemoryHealthLatencyStatsRepository(),
                    InMemoryQuotaSnapshotRepository(),
                    InMemoryTenantEntitlementRepository(),
                    InMemoryMemoryRepository(),
                    InMemoryConversationRepository(),
                    adapterRegistry,
                    clock,
                    ids,
                    events,
                    events,
                    optInToStubs = true,
                ).build()

            val request =
                CanonicalRequest(
                    requestId = requestId,
                    tenantId = tenantId,
                    principal = "user-1",
                    capabilityId = capabilityId,
                    input = listOf(ContentPart.Text("hello")),
                    timeoutBudget = Duration.ofSeconds(30),
                    traceId = "trace-1",
                )

            val response = engine.execute(request)

            assertTrue(response.output.isNotEmpty())
            assertEquals(providerId, response.resolvedProvider)
            assertEquals(modelId, response.resolvedModel)
            assertTrue(events.publishedEvents.any { it is RequestCompleted })
        }

    /**
     * 完了条件の直接的な証跡: P6でPrompt/ContextがoptInToStubsの対象から外れたこと。
     * `optInToStubs`未指定（既定false）でもPromptEngine/ContextManagerの構築自体は成功し、
     * 例外はそれより後段のCacheEngine（P7未着手のまま）でのみ発生することを確認する。
     */
    @Test
    fun `build succeeds past Prompt and Context construction without optInToStubs`() {
        val events = InMemoryDomainEventPublisher()
        val composer =
            ExecutionEngineComposer(
                InMemoryProviderRepository(),
                InMemoryModelRepository(),
                InMemoryAliasRepository(),
                InMemoryPolicyRepository(),
                InMemoryHealthLatencyStatsRepository(),
                InMemoryQuotaSnapshotRepository(),
                InMemoryTenantEntitlementRepository(),
                InMemoryMemoryRepository(),
                InMemoryConversationRepository(),
                object : AdapterRegistry {
                    override fun resolve(pluginId: String): ResolvedPlugin = throw PluginNotFoundException(pluginId)
                },
                InMemoryClock(Instant.parse("2026-01-01T00:00:00Z")),
                InMemoryIdGenerator(),
                events,
                events,
            )

        val exception = assertThrows(IllegalStateException::class.java) { composer.build() }
        assertTrue(exception.message.orEmpty().contains("CacheEngine"))
    }

    /**
     * 着手前レビュー: ContextManager.buildが実フローで「呼ばれていない」問題の修正（読み取り側のみ）。
     * conversationIdが指定されたリクエストで、Conversationの既存Turnの内容が実際にAdapterへ送る
     * AdapterRequest.inputへ合成されることを確認する（02_システム仕様.md 2.8 step2 / 2.16）。
     */
    @Suppress("LongMethod")
    @Test
    fun `conversation history is merged into the prompt actually sent to the adapter`() =
        runBlocking {
            val providerRepository = InMemoryProviderRepository()
            val modelRepository = InMemoryModelRepository()
            val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
            val ids = InMemoryIdGenerator()
            val events = InMemoryDomainEventPublisher()
            val conversationRepository = InMemoryConversationRepository()

            providerRepository.save(
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
            modelRepository.save(
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

            val conversationId = ConversationId("01ARZ3NDEKTSV4RRFFQ69G5FA6")
            conversationRepository.save(
                Conversation(
                    conversationId = conversationId,
                    sessionId = SessionId("01ARZ3NDEKTSV4RRFFQ69G5FA5"),
                    tenantId = tenantId,
                ),
            )
            conversationRepository.appendTurn(
                conversationId,
                Turn(
                    turnId = "t1",
                    seq = 1,
                    role = TurnRole.USER,
                    contentParts = listOf(ContentPart.Text("SECRET_HISTORY_MARKER")),
                    createdAt = clock.now(),
                ),
            )

            val mockAdapter = MockProviderAdapter(MockAdapterConfig(supportedCapabilities = setOf(capabilityId)))
            mockAdapter.initialize(
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
            val capturingAdapter = CapturingProviderAdapter(mockAdapter)
            val adapterRegistry =
                object : AdapterRegistry {
                    override fun resolve(pluginId: String): ResolvedPlugin {
                        if (pluginId != "plugin-a") throw PluginNotFoundException(pluginId)
                        return ResolvedPlugin(
                            capturingAdapter,
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

            val engine =
                ExecutionEngineComposer(
                    providerRepository,
                    modelRepository,
                    InMemoryAliasRepository(),
                    InMemoryPolicyRepository(),
                    InMemoryHealthLatencyStatsRepository(),
                    InMemoryQuotaSnapshotRepository(),
                    InMemoryTenantEntitlementRepository(),
                    InMemoryMemoryRepository(),
                    conversationRepository,
                    adapterRegistry,
                    clock,
                    ids,
                    events,
                    events,
                    optInToStubs = true,
                ).build()

            val request =
                CanonicalRequest(
                    requestId = requestId,
                    tenantId = tenantId,
                    principal = "user-1",
                    capabilityId = capabilityId,
                    input = listOf(ContentPart.Text("current turn")),
                    conversationId = conversationId,
                    timeoutBudget = Duration.ofSeconds(30),
                    traceId = "trace-1",
                )

            engine.execute(request)

            val sentTexts =
                capturingAdapter.capturedRequest
                    ?.input
                    .orEmpty()
                    .filterIsInstance<ContentPart.Text>()
                    .map { it.text }
            assertTrue(sentTexts.contains("SECRET_HISTORY_MARKER"))
            assertTrue(sentTexts.contains("current turn"))
        }

    /**
     * 着手前レビュー修正: build()がcontext window超過を検出した場合、ExecutionFailedException
     * （CONTEXT_LENGTH_EXCEEDED）としてExecutionEngine呼び出し側まで伝播することを確認する。
     */
    @Suppress("LongMethod")
    @Test
    fun `context length exceeded even after compaction surfaces as ExecutionFailedException`() =
        runBlocking {
            val providerRepository = InMemoryProviderRepository()
            val modelRepository = InMemoryModelRepository()
            val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
            val ids = InMemoryIdGenerator()
            val events = InMemoryDomainEventPublisher()

            providerRepository.save(
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
            // Tiny contextWindow: even a short input's heuristic token estimate exceeds the budget
            // (contextWindow - maxOutputTokens - 15% margin) with no history involved.
            modelRepository.save(
                Model(
                    modelId = modelId,
                    providerId = providerId,
                    modelName = "model-a",
                    version = "1.0",
                    capabilities = listOf(ModelCapability(capabilityId)),
                    contextWindow = 5,
                    maxOutputTokens = 1,
                    regions = setOf(region),
                    status = ModelStatus.ACTIVE,
                    priority = 50,
                ),
            )

            val adapter = MockProviderAdapter(MockAdapterConfig(supportedCapabilities = setOf(capabilityId)))
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

            val engine =
                ExecutionEngineComposer(
                    providerRepository,
                    modelRepository,
                    InMemoryAliasRepository(),
                    InMemoryPolicyRepository(),
                    InMemoryHealthLatencyStatsRepository(),
                    InMemoryQuotaSnapshotRepository(),
                    InMemoryTenantEntitlementRepository(),
                    InMemoryMemoryRepository(),
                    InMemoryConversationRepository(),
                    adapterRegistry,
                    clock,
                    ids,
                    events,
                    events,
                    optInToStubs = true,
                ).build()

            val request =
                CanonicalRequest(
                    requestId = requestId,
                    tenantId = tenantId,
                    principal = "user-1",
                    capabilityId = capabilityId,
                    input = listOf(ContentPart.Text("this input is far too long for a 5-token context window")),
                    timeoutBudget = Duration.ofSeconds(30),
                    traceId = "trace-1",
                )

            val exception =
                assertThrows(ExecutionFailedException::class.java) {
                    runBlocking { engine.execute(request) }
                }
            assertEquals(ErrorCode.CONTEXT_LENGTH_EXCEEDED, exception.error.code)
        }

    /** [MockProviderAdapter]に実際に届いた[AdapterRequest]を記録するテスト用ラッパー。 */
    private class CapturingProviderAdapter(
        private val delegate: ProviderAdapter,
    ) : ProviderAdapter by delegate {
        var capturedRequest: AdapterRequest? = null
            private set

        override suspend fun execute(request: AdapterRequest): AdapterResponse {
            capturedRequest = request
            return delegate.execute(request)
        }
    }
}
