package apap.runtime

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.mock.MockProviderAdapter
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.AdapterErrorCategory
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
import apap.domain.model.cost.PriceBook
import apap.domain.model.cost.PriceEntry
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
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import apap.execution.ExecutionFailedException
import apap.execution.retry.RetryConfig
import apap.provider.AdapterRegistry
import apap.provider.PluginNotFoundException
import apap.provider.ResolvedPlugin
import apap.testkit.inmemory.InMemoryAliasRepository
import apap.testkit.inmemory.InMemoryBudgetRepository
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryConversationRepository
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryHealthLatencyStatsRepository
import apap.testkit.inmemory.InMemoryIdGenerator
import apap.testkit.inmemory.InMemoryMemoryRepository
import apap.testkit.inmemory.InMemoryModelRepository
import apap.testkit.inmemory.InMemoryPolicyRepository
import apap.testkit.inmemory.InMemoryPriceBookRepository
import apap.testkit.inmemory.InMemoryProviderRepository
import apap.testkit.inmemory.InMemoryQuotaPolicyRepository
import apap.testkit.inmemory.InMemoryQuotaSnapshotRepository
import apap.testkit.inmemory.InMemoryTenantEntitlementRepository
import apap.testkit.inmemory.InMemoryUsageRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * apap-runtimeコンポジションルート（[ExecutionEngineComposer]）の配線が実際に組み上がり、
 * 02_システム仕様.md 2.8のRequest Flowを最小構成（adapter-mock、実Providerへは接続しない）で
 * 一気通貫に実行できることを確認するスモークテスト。
 */
@Suppress("LargeClass")
class ExecutionEngineComposerTest {
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    private val capabilityId = CapabilityId("chat")
    private val providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA2")
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA3")
    private val requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FA4")

    /**
     * ADR-0021: 単価未登録のModelはRouting候補から除外される。costEngine/routingCostEstimatorを
     * 実配線する以上、cost計算に無関心なスモークテストでもダミーの単価表を用意する必要がある
     * （用意しないとRoutingが候補ゼロでNoCandidateAvailableExceptionを送出してしまう）。
     */
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
                    priceBookRepository(modelId),
                    InMemoryBudgetRepository(),
                    InMemoryUsageRepository(),
                    InMemoryQuotaPolicyRepository(),
                    adapterRegistry,
                    clock,
                    ids,
                    events,
                    events,
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
     * 着手前レビュー: Turn永続化（2.8 step11）の解消。conversation_id指定の連続リクエストで、
     * 履歴（user turn 1件+assistant turn 1件、seq 1〜4）が実際に蓄積されることを確認する。
     */
    @Suppress("LongMethod")
    @Test
    fun `sequential requests with the same conversationId accumulate history`() =
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

            val conversationId = ConversationId("01ARZ3NDEKTSV4RRFFQ69G5FA6")
            conversationRepository.save(
                Conversation(
                    conversationId = conversationId,
                    sessionId = SessionId("01ARZ3NDEKTSV4RRFFQ69G5FA5"),
                    tenantId = tenantId,
                ),
            )

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
                    priceBookRepository(modelId),
                    InMemoryBudgetRepository(),
                    InMemoryUsageRepository(),
                    InMemoryQuotaPolicyRepository(),
                    adapterRegistry,
                    clock,
                    ids,
                    events,
                    events,
                ).build()

            fun request(text: String): CanonicalRequest {
                val requestIdValue = if (text == "first") "01ARZ3NDEKTSV4RRFFQ69G5FA7" else "01ARZ3NDEKTSV4RRFFQ69G5FA8"
                return CanonicalRequest(
                    requestId = RequestId(requestIdValue),
                    tenantId = tenantId,
                    principal = "user-1",
                    capabilityId = capabilityId,
                    input = listOf(ContentPart.Text(text)),
                    conversationId = conversationId,
                    timeoutBudget = Duration.ofSeconds(30),
                    traceId = "trace-1",
                )
            }

            engine.execute(request("first"))
            engine.execute(request("second"))

            val turns = conversationRepository.findTurns(conversationId, tenantId, 1..Int.MAX_VALUE)
            assertEquals(4, turns.size)
            assertEquals(listOf(1, 2, 3, 4), turns.map { it.seq })
            assertEquals(
                listOf(TurnRole.USER, TurnRole.ASSISTANT, TurnRole.USER, TurnRole.ASSISTANT),
                turns.map { it.role },
            )
        }

    /**
     * 着手前レビュー: Turn永続化の失敗（未startのconversationIdを指定、
     * ConversationManager.appendTurnがConversationNotFoundExceptionを送出する）が応答を
     * 失敗させないことを確認する。
     */
    @Suppress("LongMethod")
    @Test
    fun `a turn persistence failure does not fail the response`() =
        runBlocking {
            val providerRepository = InMemoryProviderRepository()
            val modelRepository = InMemoryModelRepository()
            val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
            val ids = InMemoryIdGenerator()
            val events = InMemoryDomainEventPublisher()
            // Deliberately never saved: appendTurn will throw ConversationNotFoundException.
            val conversationRepository = InMemoryConversationRepository()
            val unknownConversationId = ConversationId("01ARZ3NDEKTSV4RRFFQ69G5FA9")

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
                    conversationRepository,
                    priceBookRepository(modelId),
                    InMemoryBudgetRepository(),
                    InMemoryUsageRepository(),
                    InMemoryQuotaPolicyRepository(),
                    adapterRegistry,
                    clock,
                    ids,
                    events,
                    events,
                ).build()

            val request =
                CanonicalRequest(
                    requestId = requestId,
                    tenantId = tenantId,
                    principal = "user-1",
                    capabilityId = capabilityId,
                    input = listOf(ContentPart.Text("hello")),
                    conversationId = unknownConversationId,
                    timeoutBudget = Duration.ofSeconds(30),
                    traceId = "trace-1",
                )

            val response = engine.execute(request)

            assertTrue(response.output.isNotEmpty())
        }

    /**
     * 着手前レビュー: 冪等性要件（1リクエスト=user turn 1件+assistant turn 1件）。Retry
     * （最初のProviderが3回とも失敗）とFallback（2段目のProviderで成功）を経ても、記録される
     * turnが重複しないことを確認する（最も壊れやすい箇所、というタスク要求への直接的な証跡）。
     */
    @Suppress("LongMethod")
    @Test
    fun `retries and fallback do not duplicate turn persistence`() =
        runBlocking {
            val providerRepository = InMemoryProviderRepository()
            val modelRepository = InMemoryModelRepository()
            val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
            val ids = InMemoryIdGenerator()
            val events = InMemoryDomainEventPublisher()
            val conversationRepository = InMemoryConversationRepository()

            val failingProviderId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FB0")
            val healthyProviderId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FB1")
            val failingModelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FB2")
            val healthyModelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FB3")

            fun saveProvider(
                id: ProviderId,
                pluginId: String,
                priority: Int,
            ) = providerRepository.save(
                Provider(
                    providerId = id,
                    name = "provider-${id.value}",
                    adapterPluginId = pluginId,
                    spiVersion = SemVer(1, 0, 0),
                    endpoints = listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                    authType = "api_key",
                    credentialRefs = listOf(CredentialRef("secret-ref", 1, CredentialState.ACTIVE)),
                    rateLimits = RateLimits(600, 100_000, 10),
                    priority = priority,
                    regions = setOf(region),
                    status = ProviderStatus.ACTIVE,
                ),
            )

            fun saveModel(
                id: ModelId,
                pid: ProviderId,
            ) = modelRepository.save(
                Model(
                    modelId = id,
                    providerId = pid,
                    modelName = "model-${id.value}",
                    version = "1.0",
                    capabilities = listOf(ModelCapability(capabilityId)),
                    contextWindow = 8000,
                    maxOutputTokens = 1000,
                    regions = setOf(region),
                    status = ModelStatus.ACTIVE,
                    priority = 50,
                ),
            )

            // Higher priority: routed first, tried and exhausted before Fallback moves on.
            saveProvider(failingProviderId, "plugin-failing", priority = 100)
            saveProvider(healthyProviderId, "plugin-healthy", priority = 50)
            saveModel(failingModelId, failingProviderId)
            saveModel(healthyModelId, healthyProviderId)

            fun initializedAdapter(
                id: ProviderId,
                config: MockAdapterConfig,
            ): MockProviderAdapter {
                val adapter = MockProviderAdapter(config)
                adapter.initialize(
                    AdapterConfig(
                        id,
                        listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                        RateLimits(600, 100_000, 10),
                        setOf(region),
                    ),
                    object : SecretAccessor {
                        override fun resolve(ref: CredentialRef): SecretValue = SecretValue("secret".toCharArray())
                    },
                )
                return adapter
            }

            val failingAdapter =
                initializedAdapter(
                    failingProviderId,
                    MockAdapterConfig(
                        supportedCapabilities = setOf(capabilityId),
                        forcedErrorCategory = AdapterErrorCategory.TRANSIENT,
                    ),
                )
            val healthyConfig = MockAdapterConfig(supportedCapabilities = setOf(capabilityId))
            val healthyAdapter = initializedAdapter(healthyProviderId, healthyConfig)

            fun manifest(pluginId: String) =
                PluginManifest(
                    pluginId,
                    SemVer(1, 0, 0),
                    SemVerRange(listOf(SemVerRange.Comparator(SemVerRange.Op.GTE, SemVer(1, 0, 0)))),
                    "apap.adapter.mock.MockProviderAdapter",
                    setOf(capabilityId),
                    setOf("api_key"),
                    "sig",
                )
            val adapterRegistry =
                object : AdapterRegistry {
                    override fun resolve(pluginId: String): ResolvedPlugin =
                        when (pluginId) {
                            "plugin-failing" -> ResolvedPlugin(failingAdapter, manifest(pluginId))
                            "plugin-healthy" -> ResolvedPlugin(healthyAdapter, manifest(pluginId))
                            else -> throw PluginNotFoundException(pluginId)
                        }
                }

            val conversationId = ConversationId("01ARZ3NDEKTSV4RRFFQ69G5FB4")
            conversationRepository.save(
                Conversation(
                    conversationId = conversationId,
                    sessionId = SessionId("01ARZ3NDEKTSV4RRFFQ69G5FB5"),
                    tenantId = tenantId,
                ),
            )

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
                    priceBookRepository(failingModelId, healthyModelId),
                    InMemoryBudgetRepository(),
                    InMemoryUsageRepository(),
                    InMemoryQuotaPolicyRepository(),
                    adapterRegistry,
                    clock,
                    ids,
                    events,
                    events,
                    retryConfig = RetryConfig(maxAttempts = 3, baseBackoffMs = 1),
                ).build()

            val request =
                CanonicalRequest(
                    requestId = requestId,
                    tenantId = tenantId,
                    principal = "user-1",
                    capabilityId = capabilityId,
                    input = listOf(ContentPart.Text("hello")),
                    conversationId = conversationId,
                    timeoutBudget = Duration.ofSeconds(30),
                    traceId = "trace-1",
                )

            val response = engine.execute(request)

            assertEquals(healthyProviderId, response.resolvedProvider)
            val turns = conversationRepository.findTurns(conversationId, tenantId, 1..Int.MAX_VALUE)
            assertEquals(2, turns.size)
            assertEquals(listOf(TurnRole.USER, TurnRole.ASSISTANT), turns.map { it.role })
        }

    /**
     * 完了条件の直接的な証跡: P5〜P7を通じてPrompt/Context/Cache/Costの全スタブが実装へ置き換わり、
     * `optInToStubs`パラメータ自体が削除されたこと（このコンストラクタ呼出が引数無しで成功すれば、
     * `optInToStubs`という名前付き引数はコンパイル時にそもそも存在し得ない）。
     */
    @Test
    fun `build succeeds with no optInToStubs parameter at all`() {
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
                InMemoryPriceBookRepository(),
                InMemoryBudgetRepository(),
                InMemoryUsageRepository(),
                InMemoryQuotaPolicyRepository(),
                object : AdapterRegistry {
                    override fun resolve(pluginId: String): ResolvedPlugin = throw PluginNotFoundException(pluginId)
                },
                InMemoryClock(Instant.parse("2026-01-01T00:00:00Z")),
                InMemoryIdGenerator(),
                events,
                events,
            )

        assertDoesNotThrow { composer.build() }
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
                tenantId,
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
                    priceBookRepository(modelId),
                    InMemoryBudgetRepository(),
                    InMemoryUsageRepository(),
                    InMemoryQuotaPolicyRepository(),
                    adapterRegistry,
                    clock,
                    ids,
                    events,
                    events,
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
                    priceBookRepository(modelId),
                    InMemoryBudgetRepository(),
                    InMemoryUsageRepository(),
                    InMemoryQuotaPolicyRepository(),
                    adapterRegistry,
                    clock,
                    ids,
                    events,
                    events,
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
