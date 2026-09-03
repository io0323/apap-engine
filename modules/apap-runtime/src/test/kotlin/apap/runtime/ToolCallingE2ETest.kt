package apap.runtime

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.mock.MockProviderAdapter
import apap.adapter.spi.AdapterChunk
import apap.adapter.spi.AdapterChunkType
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.ProviderToolFormat
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.adapter.spi.plugin.PluginManifest
import apap.adapter.spi.plugin.SemVerRange
import apap.api.ApapRequest
import apap.api.ApapStreamChunkType
import apap.domain.model.cost.PriceBook
import apap.domain.model.cost.PriceEntry
import apap.domain.model.execution.ToolDefinition
import apap.domain.model.execution.ToolResult
import apap.domain.model.modelcatalog.ModelCapability
import apap.domain.model.modelcatalog.ModelStatus
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.FinishReason
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.TenantId
import apap.provider.AdapterRegistry
import apap.provider.PluginNotFoundException
import apap.provider.RegisterModelCommand
import apap.provider.RegisterProviderCommand
import apap.provider.ResolvedPlugin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import apap.adapter.spi.ToolCall as SpiToolCall

/**
 * 05_シーケンス設計.md 5.4 Tool Calling / 5.5 Function Calling のE2E検証（FR-CAP-005）。
 *
 * ## なぜE2Eでなければならないか
 *
 * P11時点で、Tool Callingは往路（`tools`→Adapter）も復路（`toolCalls`→公開DTO）も
 * 配線されていたが、**この経路を通るテストが1件も無かった**。単体テストは
 * `ToolCallAssembler`の中括弧数え上げを検証していたが、それは
 * 「アセンブラ単体が正しい」ことしか示さない——実際にStreamingEngine経由で
 * Adapterのチャンクが渡り、公開APIまで組み上がるかは未検証だった。
 *
 * レート制限の破綻は429で騒がしく失敗するが、**Tool Callingの破綻は静かに誤った引数を返す**。
 * したがって組立て結果の中身（引数JSONの一致）まで確認する。
 *
 * ## 検証項目
 *
 * 1. 共通Tool定義がAdapterへ渡り、`translateTools`が呼ばれること
 * 2. ToolCall指示が正規化されて返ること（`finishReason=TOOL_CALL`と引数）
 * 3. `toolResults`を返すと最終応答が得られること（往復2回）
 * 4. Streaming経路でToolCallデルタが正しく組み上がること
 *    （文字列内のブレース、エスケープ引用符、複数tool call並行、未終端のエラー化）
 */
class ToolCallingE2ETest {
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FF0")
    private val capabilityId = CapabilityId("chat")

    /** 文字列リテラル内に`{`/`}`とエスケープ引用符を含む引数。素朴な数え上げなら壊れる。 */
    private val trickyArguments =
        """{"query":"a {nested} \"quoted\" brace }","limit":3}"""

    @Test
    fun `5-4 - tools reach the adapter and a tool call round trip completes`() {
        val translated = AtomicReference<List<String>>(emptyList())
        val fixture =
            fixture(
                MockAdapterConfig(
                    supportedCapabilities = setOf(capabilityId),
                    toolCallsOnFirstTurn = listOf(SpiToolCall("call-1", "search", trickyArguments)),
                ),
                translatedNames = translated,
            )

        fixture.engine.use { engine ->
            runBlocking {
                setUpActiveProviderAndModel(engine, fixture.repositories)

                // --- 1往復目: Tool定義を渡すとToolCall指示が返る ---
                val first = engine.execute(requestWithTools())

                assertEquals(
                    listOf("search"),
                    translated.get(),
                    "共通Tool定義がAdapterのtranslateToolsへ渡っていません（往路が繋がっていない）。",
                )
                assertEquals(FinishReason.TOOL_CALL, first.finishReason)
                val calls = first.toolCalls.orEmpty()
                assertEquals(1, calls.size, "ToolCall指示が正規化されて返っていません。")
                val call = calls.first()
                assertEquals("call-1", call.callId)
                assertEquals("search", call.toolName)
                assertEquals(
                    trickyArguments,
                    call.arguments,
                    "ToolCallの引数が改変されています。誤った引数は静かに誤動作を招きます。",
                )

                // --- 2往復目: tool結果を返すと最終応答になる ---
                val second =
                    engine.execute(
                        requestWithTools().copy(
                            toolResults = listOf(ToolResult(callId = call.callId, content = "sunny")),
                        ),
                    )
                assertEquals(FinishReason.COMPLETED, second.finishReason)
                assertTrue(
                    second.output.filterIsInstance<ContentPart.Text>().any { "sunny" in it.text },
                    "tool結果が最終応答へ反映されていません（往復が閉じていない）: ${second.output}",
                )
            }
        }
    }

    @Test
    fun `5-4 - streaming assembles tool call deltas across chunks including braces and escapes`() {
        // 引数を意図的に「文字列内の}で切れる」位置で分割する。1チャンク目だけを見ると
        // 中括弧が閉じたように見えるため、素朴な数え上げなら早すぎる確定をしてしまう。
        val head = """{"query":"a {nested} """
        val tail = """\"quoted\" brace }","limit":3}"""
        val fixture =
            fixture(
                MockAdapterConfig(
                    supportedCapabilities = setOf(capabilityId),
                    streamChunks =
                        listOf(
                            toolDelta("call-1", "search", head),
                            toolDelta("call-2", "lookup", """{"id":"""),
                            toolDelta("call-1", "search", tail),
                            toolDelta("call-2", "lookup", """7}"""),
                            AdapterChunk(type = AdapterChunkType.MESSAGE_END, index = 4),
                        ),
                ),
            )

        fixture.engine.use { engine ->
            runBlocking {
                setUpActiveProviderAndModel(engine, fixture.repositories)
                val chunks = engine.executeStream(requestWithTools()).toList()

                val assembled =
                    chunks
                        .filter { it.type == ApapStreamChunkType.TOOL_CALL_DELTA }
                        .mapNotNull { it.toolCallDelta }
                        .associate { it.callId to it.arguments }

                // 2つのtool callが独立に追跡され、それぞれ完成形で1回ずつ出ること。
                assertEquals(
                    setOf("call-1", "call-2"),
                    assembled.keys,
                    "並行する複数のtool callが組み上がっていません: $assembled",
                )
                assertEquals(
                    trickyArguments,
                    assembled["call-1"],
                    "文字列リテラル内のブレース・エスケープ引用符をまたぐ組立てが壊れています。",
                )
                assertEquals("""{"id":7}""", assembled["call-2"])
            }
        }
    }

    @Test
    fun `5-4 - an unterminated tool call is surfaced as an error instead of a truncated argument`() {
        val fixture =
            fixture(
                MockAdapterConfig(
                    supportedCapabilities = setOf(capabilityId),
                    // 閉じ括弧が来ないままMESSAGE_ENDに到達する。
                    streamChunks =
                        listOf(
                            toolDelta("call-1", "search", """{"query":"unterminated"""),
                            AdapterChunk(type = AdapterChunkType.MESSAGE_END, index = 1),
                        ),
                ),
            )

        fixture.engine.use { engine ->
            runBlocking {
                setUpActiveProviderAndModel(engine, fixture.repositories)
                val chunks = engine.executeStream(requestWithTools()).toList()

                val completed = chunks.filter { it.type == ApapStreamChunkType.TOOL_CALL_DELTA }
                assertTrue(
                    completed.isEmpty(),
                    "未終端のtool callを完成扱いで送出しています。切り詰められた引数は" +
                        "呼び出し側から見て正常な引数と区別できません: $completed",
                )
                assertTrue(
                    chunks.any { it.type == ApapStreamChunkType.ERROR },
                    "未終端のtool callがエラーとして表面化していません: ${chunks.map { it.type }}",
                )
            }
        }
    }

    private fun toolDelta(
        callId: String,
        name: String,
        args: String,
    ) = AdapterChunk(
        type = AdapterChunkType.TOOL_CALL_DELTA,
        index = 0,
        toolCallDelta = SpiToolCall(callId, name, args),
    )

    private fun requestWithTools() =
        ApapRequest(
            tenantId = tenantId,
            principal = "user-1",
            capabilityId = capabilityId,
            input = listOf(ContentPart.Text("what is the weather")),
            tools = listOf(ToolDefinition("search", "search things", """{"type":"object"}""")),
        )

    private class Fixture(
        val engine: ApapEngine,
        val repositories: ApapRepositories,
    )

    private fun fixture(
        config: MockAdapterConfig,
        translatedNames: AtomicReference<List<String>>? = null,
    ): Fixture {
        val repositories = ApapRepositories()
        val engine =
            ApapEngineBuilder(repositories = repositories)
                .adapterRegistry(adapterRegistry(config, translatedNames))
                .build()
        return Fixture(engine, repositories)
    }

    private fun adapterRegistry(
        config: MockAdapterConfig,
        translatedNames: AtomicReference<List<String>>?,
    ): AdapterRegistry {
        val mock = MockProviderAdapter(config)
        mock.initialize(
            AdapterConfig(
                ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FF1"),
                listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                RateLimits(6_000, 1_000_000, 100),
                setOf(region),
            ),
            object : SecretAccessor {
                override fun resolve(ref: CredentialRef): SecretValue = SecretValue("secret".toCharArray())
            },
        )
        val adapter = RecordingToolAdapter(mock, translatedNames)
        return object : AdapterRegistry {
            override fun resolve(pluginId: String): ResolvedPlugin {
                if (pluginId != "plugin-a") throw PluginNotFoundException(pluginId)
                return ResolvedPlugin(
                    adapter,
                    PluginManifest(
                        pluginId = "plugin-a",
                        version = SemVer(1, 0, 0),
                        spiVersionRange = SemVerRange.parse(">=1.0"),
                        entryPoint = "test.Entry",
                        capabilities = setOf(capabilityId),
                        authTypes = setOf("api_key"),
                        signature = "sig",
                    ),
                )
            }
        }
    }

    /** `translateTools`に渡ったTool名を記録する。往路が繋がっていることの直接の証拠になる。 */
    private class RecordingToolAdapter(
        private val delegate: ProviderAdapter,
        private val translatedNames: AtomicReference<List<String>>?,
    ) : ProviderAdapter by delegate {
        override fun translateTools(tools: List<apap.adapter.spi.ToolDefinition>): ProviderToolFormat {
            translatedNames?.set(tools.map { it.name })
            return delegate.translateTools(tools)
        }

        override suspend fun execute(request: AdapterRequest): apap.adapter.spi.AdapterResponse {
            // 実Adapterと同じく、送信前にTool定義をProvider形式へ変換する（5.4のRM→ADの流れ）。
            request.tools?.let { translateTools(it) }
            return delegate.execute(request)
        }

        override suspend fun executeStream(request: AdapterRequest): ProviderAdapter.AdapterStream {
            request.tools?.let { translateTools(it) }
            return delegate.executeStream(request)
        }
    }

    private suspend fun setUpActiveProviderAndModel(
        engine: ApapEngine,
        repositories: ApapRepositories,
    ) {
        val provider =
            engine.admin.providers.register(
                RegisterProviderCommand(
                    name = "provider-a",
                    adapterPluginId = "plugin-a",
                    spiVersion = SemVer(1, 0, 0),
                    endpoints = listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                    authType = "api_key",
                    credentialRefs = listOf(CredentialRef("secret-ref", 1, CredentialState.STANDBY)),
                    rateLimits = RateLimits(6_000, 1_000_000, 100),
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
    }
}
