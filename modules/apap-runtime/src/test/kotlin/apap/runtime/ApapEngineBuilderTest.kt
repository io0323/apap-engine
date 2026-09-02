package apap.runtime

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.mock.MockProviderAdapter
import apap.adapter.mock.ScriptedOutcome
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.adapter.spi.plugin.PluginManifest
import apap.adapter.spi.plugin.SemVerRange
import apap.api.ApapException
import apap.api.ApapRequest
import apap.context.CompactionResult
import apap.context.CompactionStrategy
import apap.domain.model.conversation.Turn
import apap.domain.model.cost.PriceBook
import apap.domain.model.cost.PriceEntry
import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.NormalizedError
import apap.domain.model.vo.Period
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.Score
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.service.routing.Candidate
import apap.domain.service.routing.RoutingWeights
import apap.domain.service.routing.ScoredCandidate
import apap.execution.retry.RetryStrategy
import apap.provider.AdapterRegistry
import apap.provider.PluginNotFoundException
import apap.provider.RegisterModelCommand
import apap.provider.RegisterProviderCommand
import apap.provider.ResolvedPlugin
import apap.routing.spi.RoutingStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P9着手前レビュー: [ApapEngineBuilder]（唯一のコンポジションルート）の検証。
 * - 依存ゼロ構成（[ApapRepositories]既定値 + adapter-mock）でChat/Streaming/Embeddingが動くこと
 * - `close()`のライフサイクル（新規拒否・実行中完遂・冪等性）
 * - 各SPI差替点が実際に挙動を変えること
 */
class ApapEngineBuilderTest {
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FD0")

    private fun manifest(capabilityId: CapabilityId) =
        PluginManifest(
            pluginId = "plugin-a",
            version = SemVer(1, 0, 0),
            spiVersionRange = SemVerRange.parse(">=1.0"),
            entryPoint = "test.Entry",
            capabilities = setOf(capabilityId),
            authTypes = setOf("api_key"),
            signature = "sig",
        )

    private fun adapterRegistryOf(
        capabilityId: CapabilityId,
        adapterConfig: MockAdapterConfig,
    ): AdapterRegistry {
        val adapter = MockProviderAdapter(adapterConfig)
        adapter.initialize(
            AdapterConfig(
                ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FD1"),
                listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                RateLimits(600, 100_000, 10),
                setOf(region),
            ),
            object : SecretAccessor {
                override fun resolve(ref: CredentialRef): SecretValue = SecretValue("secret".toCharArray())
            },
        )
        return object : AdapterRegistry {
            override fun resolve(pluginId: String): ResolvedPlugin {
                if (pluginId != "plugin-a") throw PluginNotFoundException(pluginId)
                return ResolvedPlugin(adapter, manifest(capabilityId))
            }
        }
    }

    private fun mockAdapterRegistryFor(capabilityId: CapabilityId): AdapterRegistry =
        adapterRegistryOf(capabilityId, MockAdapterConfig(supportedCapabilities = setOf(capabilityId)))

    /**
     * ProviderをACTIVEにし、指定Capabilityを持つACTIVEなModelを1件登録して返す。
     * [RealCostEstimator]（`ApapEngineBuilder`既定のCostEstimator）はADR-0021により単価未登録の
     * Modelを候補から除外するため（`apap.routing.NoCandidateAvailableException`）、
     * [repositories]へ広範な有効期間のPriceEntryも合わせて登録する。
     */
    private suspend fun setUpActiveProviderAndModel(
        engine: ApapEngine,
        repositories: ApapRepositories,
        capabilityId: CapabilityId,
    ): ModelId {
        val provider =
            engine.admin.providers.register(
                RegisterProviderCommand(
                    name = "provider-a",
                    adapterPluginId = "plugin-a",
                    spiVersion = SemVer(1, 0, 0),
                    endpoints = listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                    authType = "api_key",
                    credentialRefs = listOf(CredentialRef("secret-ref", 1, CredentialState.STANDBY)),
                    rateLimits = RateLimits(600, 100_000, 10),
                    priority = 50,
                    regions = setOf(region),
                ),
            )
        engine.admin.providers.beginValidation(provider.providerId)
        engine.admin.providers.completeValidation(provider.providerId)
        engine.admin.providers.enable(provider.providerId, "test setup")

        val model =
            engine.admin.models.register(
                RegisterModelCommand(
                    providerId = provider.providerId,
                    modelName = "model-a",
                    version = "1.0",
                    capabilities = listOf(ModelCapability(capabilityId)),
                    contextWindow = 8000,
                    maxOutputTokens = 1000,
                    regions = setOf(region),
                    priority = 50,
                ),
            )
        engine.admin.models.changeStatus(model.modelId, ModelStatus.TESTING)
        engine.admin.models.changeStatus(model.modelId, ModelStatus.ACTIVE)

        repositories.priceBookRepository.save(
            PriceBook(
                priceBookId = "pb-${model.modelId.value}",
                entries =
                    listOf(
                        PriceEntry(
                            modelId = model.modelId,
                            inputPer1k = Money(BigDecimal("0.01"), "USD"),
                            outputPer1k = Money(BigDecimal("0.01"), "USD"),
                            period = Period(Instant.EPOCH, Instant.parse("9999-01-01T00:00:00Z")),
                        ),
                    ),
            ),
        )
        return model.modelId
    }

    private fun request(
        capabilityId: CapabilityId,
        text: String = "hello",
    ) = ApapRequest(
        tenantId = tenantId,
        principal = "user-1",
        capabilityId = capabilityId,
        input = listOf(ContentPart.Text(text)),
    )

    @Test
    fun `zero-dependency build works end to end for chat with adapter-mock`(): Unit =
        runBlocking {
            val capabilityId = CapabilityId("chat")
            val repositories = ApapRepositories()
            val engine =
                ApapEngineBuilder(repositories = repositories)
                    .adapterRegistry(mockAdapterRegistryFor(capabilityId))
                    .build()
            setUpActiveProviderAndModel(engine, repositories, capabilityId)

            val response = engine.execute(request(capabilityId))

            assertTrue(response.output.isNotEmpty())
            engine.close()
        }

    @Test
    fun `zero-dependency build works end to end for streaming chat with adapter-mock`(): Unit =
        runBlocking {
            val capabilityId = CapabilityId("chat")
            val repositories = ApapRepositories()
            val engine =
                ApapEngineBuilder(repositories = repositories)
                    .adapterRegistry(mockAdapterRegistryFor(capabilityId))
                    .build()
            setUpActiveProviderAndModel(engine, repositories, capabilityId)

            val chunks = engine.executeStream(request(capabilityId)).toList()

            assertTrue(chunks.isNotEmpty())
            engine.close()
        }

    @Test
    fun `zero-dependency build works end to end for embedding with adapter-mock`(): Unit =
        runBlocking {
            val capabilityId = CapabilityId("embedding")
            val repositories = ApapRepositories()
            val engine =
                ApapEngineBuilder(repositories = repositories)
                    .adapterRegistry(mockAdapterRegistryFor(capabilityId))
                    .build()
            setUpActiveProviderAndModel(engine, repositories, capabilityId)

            val response = engine.execute(request(capabilityId, "embed me"))

            assertFalse(response.cached)
            engine.close()
        }

    // 戻り値型は明示的にUnitにすること。式本体（`= runBlocking { ... }`）のまま最後の式が
    // assertThrows（Throwableを返す）だと関数の戻り値型がUnitでなくなり、JUnit 5がテストとして
    // 認識せず「成功扱いで一切実行されない」（実際にこの状態を作り込んでいた。
    // TestMethodReturnTypeTestが再発を機械検出する）。
    @Test
    fun `close rejects new requests and is idempotent`(): Unit =
        runBlocking {
            val capabilityId = CapabilityId("chat")
            val repositories = ApapRepositories()
            val engine =
                ApapEngineBuilder(repositories = repositories)
                    .adapterRegistry(mockAdapterRegistryFor(capabilityId))
                    .build()
            setUpActiveProviderAndModel(engine, repositories, capabilityId)

            engine.close()
            engine.close() // idempotent: must not throw

            assertThrows(IllegalStateException::class.java) {
                runBlocking { engine.execute(request(capabilityId)) }
            }
        }

    @Test
    fun `swapping compactionStrategy changes context assembly behavior`(): Unit =
        runBlocking {
            val capabilityId = CapabilityId("chat")
            var invoked = false
            val spyStrategy =
                object : CompactionStrategy {
                    override fun compact(
                        turns: List<Turn>,
                        budgetTokens: Int,
                        tokensOf: (List<ContentPart>) -> TokenCount,
                    ): CompactionResult {
                        invoked = true
                        return CompactionResult(turns.take(1), truncated = false)
                    }
                }
            val repositories = ApapRepositories()
            val engine =
                ApapEngineBuilder(repositories = repositories)
                    .adapterRegistry(mockAdapterRegistryFor(capabilityId))
                    .compactionStrategy(spyStrategy)
                    .build()
            setUpActiveProviderAndModel(engine, repositories, capabilityId)

            engine.execute(request(capabilityId))

            assertTrue(invoked, "the injected CompactionStrategy must be the one actually invoked by the built engine")
            engine.close()
        }

    @Test
    fun `swapping routingStrategy changes which candidate is scored highest`(): Unit =
        runBlocking {
            val capabilityId = CapabilityId("chat")
            var invoked = false
            val spyStrategy =
                object : RoutingStrategy {
                    override fun score(
                        candidates: List<Candidate>,
                        weights: RoutingWeights,
                    ): List<ScoredCandidate> {
                        invoked = true
                        return candidates.map { ScoredCandidate(it, Score(1.0)) }
                    }
                }
            val repositories = ApapRepositories()
            val engine =
                ApapEngineBuilder(repositories = repositories)
                    .adapterRegistry(mockAdapterRegistryFor(capabilityId))
                    .routingStrategy(spyStrategy)
                    .build()
            setUpActiveProviderAndModel(engine, repositories, capabilityId)

            engine.execute(request(capabilityId))

            assertTrue(invoked, "the injected RoutingStrategy must be the one actually invoked by the built engine")
            engine.close()
        }

    /** 1回目TRANSIENT失敗→2回目成功、という台本のadapter-mockを返す。 */
    private fun retryScriptedAdapterRegistryFor(capabilityId: CapabilityId): AdapterRegistry =
        adapterRegistryOf(
            capabilityId,
            MockAdapterConfig(
                supportedCapabilities = setOf(capabilityId),
                scriptedOutcomes =
                    listOf(
                        ScriptedOutcome(errorCategory = AdapterErrorCategory.TRANSIENT),
                        ScriptedOutcome(),
                    ),
            ),
        )

    @Test
    fun `injected retryStrategy drives the retry - a delay retries, null does not`(): Unit =
        runBlocking {
            val capabilityId = CapabilityId("chat")

            // (1) 遅延を返す戦略: 1回目のTRANSIENT失敗後にリトライされ、2回目で成功する。
            var retryingInvocations = 0
            val retryingRepositories = ApapRepositories()
            val retryingEngine =
                ApapEngineBuilder(repositories = retryingRepositories)
                    .adapterRegistry(retryScriptedAdapterRegistryFor(capabilityId))
                    .retryStrategy(
                        object : RetryStrategy {
                            override fun nextDelay(
                                attempt: Int,
                                error: NormalizedError,
                                retryAfter: Duration?,
                            ): Duration {
                                retryingInvocations++
                                return Duration.ZERO
                            }
                        },
                    ).build()
            setUpActiveProviderAndModel(retryingEngine, retryingRepositories, capabilityId)

            val response = retryingEngine.execute(request(capabilityId))

            assertTrue(
                retryingInvocations > 0,
                "the injected RetryStrategy must be consulted after a retryable failure",
            )
            assertTrue(response.output.isNotEmpty(), "the retried attempt must succeed and produce output")
            retryingEngine.close()

            // (2) nullを返す戦略（リトライしない）: 同じ台本でも1回目の失敗がそのまま伝播する。
            var nonRetryingInvocations = 0
            val nonRetryingRepositories = ApapRepositories()
            val nonRetryingEngine =
                ApapEngineBuilder(repositories = nonRetryingRepositories)
                    .adapterRegistry(retryScriptedAdapterRegistryFor(capabilityId))
                    .retryStrategy(
                        object : RetryStrategy {
                            override fun nextDelay(
                                attempt: Int,
                                error: NormalizedError,
                                retryAfter: Duration?,
                            ): Duration? {
                                nonRetryingInvocations++
                                return null
                            }
                        },
                    ).build()
            setUpActiveProviderAndModel(nonRetryingEngine, nonRetryingRepositories, capabilityId)

            // ApapEngine経由の失敗は公開例外へ正規化される（内部のExecutionFailedExceptionは
            // 埋込ホストから見えないため、そのまま投げるとcatchできない）。
            assertThrows(ApapException::class.java) {
                runBlocking { nonRetryingEngine.execute(request(capabilityId)) }
            }
            assertTrue(nonRetryingInvocations > 0, "the injected RetryStrategy must be consulted before giving up")
            nonRetryingEngine.close()
        }

    @Test
    fun `close drains - an in-flight request completes while new requests are rejected`(): Unit =
        runBlocking {
            val capabilityId = CapabilityId("chat")
            val inFlight = CountDownLatch(1)
            val repositories = ApapRepositories()
            val engine =
                ApapEngineBuilder(repositories = repositories)
                    .adapterRegistry(
                        adapterRegistryOf(
                            capabilityId,
                            MockAdapterConfig(
                                supportedCapabilities = setOf(capabilityId),
                                // close()がDRAINING待ちに入っている間、リクエストを実行中に留める。
                                extraDelayMillis = IN_FLIGHT_HOLD_MILLIS,
                            ),
                        ),
                    )
                    // RoutingStrategyはDefaultApapEngine.executeのinFlightカウント後に呼ばれるため、
                    // 「確実に実行中である」ことの決定的なシグナルとして使う（sleepでの推測を避ける）。
                    .routingStrategy(
                        object : RoutingStrategy {
                            override fun score(
                                candidates: List<Candidate>,
                                weights: RoutingWeights,
                            ): List<ScoredCandidate> {
                                inFlight.countDown()
                                return candidates.map { ScoredCandidate(it, Score(1.0)) }
                            }
                        },
                    ).build()
            setUpActiveProviderAndModel(engine, repositories, capabilityId)

            // execute()は別ディスパッチャで走らせる。close()はスレッドをブロックして排出を待つため、
            // 同一スレッドで待つとデッドロックする。
            val inFlightRequest = async(Dispatchers.Default) { engine.execute(request(capabilityId)) }
            assertTrue(
                inFlight.await(IN_FLIGHT_SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the request never reached routing, so it was never actually in flight",
            )

            engine.close()

            // 実行中だったリクエストは拒否されず完遂する（DRAINING→実行中完遂）。
            val response = inFlightRequest.await()
            assertTrue(
                response.output.isNotEmpty(),
                "the in-flight request must complete rather than be aborted by close()",
            )

            // close()後の新規リクエストは拒否される。
            assertThrows(IllegalStateException::class.java) {
                runBlocking { engine.execute(request(capabilityId)) }
            }
        }

    private companion object {
        const val IN_FLIGHT_HOLD_MILLIS = 300L
        const val IN_FLIGHT_SIGNAL_TIMEOUT_SECONDS = 10L
    }
}
