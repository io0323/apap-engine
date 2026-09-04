package apap.runtime.fidelity

import apap.adapter.spi.AdapterRequest
import apap.domain.model.conversation.Conversation
import apap.domain.model.conversation.Turn
import apap.domain.model.conversation.TurnRole
import apap.domain.model.execution.InputMessage
import apap.domain.model.vo.ContentPart
import apap.runtime.ApapRepositories
import apap.runtime.EngineFixture
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * リクエスト忠実性（Request Fidelity）の網羅検査。[RequestFidelityContract]の分類が
 * **実際にそのとおり動く**ことを、本番の入口（`ApapEngineBuilder`）で組んだエンジンに
 * 見張り値入りのリクエストを通し、Adapterが受け取った[AdapterRequest]を直接読んで確かめる。
 *
 * 非Streamingだけでは足りない。F4では`DefaultPromptEngine`と`ContextManager.refit`が
 * **別々に**roleを落としており、片方の経路だけを見ていては両方は見つからなかった。
 * そのため主要な検査はStreaming経路でも同じ内容を繰り返す。
 */
class RequestFidelityE2ETest {
    @Test
    fun `non-streaming - every field classified as reaching the adapter arrives with its value`() {
        withRecordedRequest { captured ->
            assertReachingFieldsArrived(captured)
        }
    }

    @Test
    fun `streaming - every field classified as reaching the adapter arrives with its value`() {
        withRecordedRequest(streaming = true) { captured ->
            assertReachingFieldsArrived(captured)
        }
    }

    @Test
    fun `non-streaming - nothing classified as not reaching the adapter appears in the request`() {
        withRecordedRequest { captured -> assertNoForbiddenValue(captured) }
    }

    @Test
    fun `streaming - nothing classified as not reaching the adapter appears in the request`() {
        withRecordedRequest(streaming = true) { captured -> assertNoForbiddenValue(captured) }
    }

    @Test
    fun `messages keep their role and order`() {
        withRecordedRequest { captured ->
            assertEquals(
                listOf(TurnRole.SYSTEM, TurnRole.USER, TurnRole.ASSISTANT, TurnRole.USER),
                captured.messages.map { it.role },
                "roleの並びが崩れています: " + captured.messages.joinToString { "${it.role}=${it.content.text()}" },
            )
            assertEquals(
                listOf(FidelitySentinels.USER_TEXT, FidelitySentinels.FOLLOW_UP_TEXT),
                captured.messages.filter { it.role == TurnRole.USER }.map { it.content.text() },
                "USER発話の内容と順序が保たれていません（発話者の帰属が混ざっている）。",
            )
        }
    }

    @Test
    fun `system prompt arrives as a system role message`() {
        withRecordedRequest { captured ->
            val system = captured.messages.filter { it.role == TurnRole.SYSTEM }
            assertEquals(1, system.size, "System Promptがsystem roleとして届いていません: ${captured.messages}")
            assertEquals(FidelitySentinels.SYSTEM_TEXT, system.single().content.text())
        }
    }

    @Test
    fun `streaming - system prompt arrives as a system role message`() {
        withRecordedRequest(streaming = true) { captured ->
            assertEquals(
                FidelitySentinels.SYSTEM_TEXT,
                captured.messages
                    .single { it.role == TurnRole.SYSTEM }
                    .content
                    .text(),
                "Streaming経路でSystem Promptが落ちています。",
            )
        }
    }

    @Test
    fun `each content modality reaches the adapter unchanged`() {
        val messages =
            listOf(
                InputMessage(
                    TurnRole.USER,
                    listOf(
                        ContentPart.Text(FidelitySentinels.USER_TEXT),
                        ContentPart.Image(FidelitySentinels.IMAGE_URI, "image/png"),
                        ContentPart.Audio(FidelitySentinels.AUDIO_URI, "audio/wav"),
                    ),
                ),
            )
        withRecordedRequest(messages = messages) { captured ->
            val parts = captured.messages.single().content
            assertEquals(3, parts.size, "modalityが欠けています: $parts")
            assertTrue(parts[0] is ContentPart.Text, "text partが落ちています: $parts")
            val image = parts[1] as? ContentPart.Image
            assertNotNull(image, "image partが落ちています（modalityごとの変換漏れ）: $parts")
            assertEquals(FidelitySentinels.IMAGE_URI, image!!.uri)
            assertEquals("image/png", image.mimeType)
            val audio = parts[2] as? ContentPart.Audio
            assertNotNull(audio, "audio partが落ちています: $parts")
            assertEquals(FidelitySentinels.AUDIO_URI, audio!!.uri)
            assertEquals("audio/wav", audio.mimeType)
            // 平坦な input 側にも同じ内容が並ぶこと（roleを見ない用途がここを読むため）。
            assertEquals(parts, captured.input, "inputとmessagesの内容がずれています。")
        }
    }

    @Test
    fun `conversation history reaches the adapter with the role of each turn`() {
        val repositories = ApapRepositories()
        repositories.conversationRepository.save(
            Conversation(
                conversationId = FidelitySentinels.CONVERSATION,
                sessionId = FidelitySentinels.SESSION,
                tenantId = EngineFixture.TENANT,
                turns =
                    listOf(
                        turn(1, TurnRole.USER, HISTORY_USER),
                        turn(2, TurnRole.ASSISTANT, HISTORY_ASSISTANT),
                    ),
            ),
        )
        withRecordedRequest(
            repositories = repositories,
            messages = listOf(InputMessage(TurnRole.USER, listOf(ContentPart.Text(FidelitySentinels.USER_TEXT)))),
            conversation = FidelitySentinels.CONVERSATION,
        ) { captured ->
            val history = captured.messages.filter { it.content.text() in setOf(HISTORY_USER, HISTORY_ASSISTANT) }
            assertEquals(
                listOf(TurnRole.USER to HISTORY_USER, TurnRole.ASSISTANT to HISTORY_ASSISTANT),
                history.map { it.role to it.content.text() },
                "会話履歴がroleを保ったまま届いていません（届いた全内容: " +
                    captured.messages.joinToString { "${it.role}=${it.content.text()}" } + "）",
            )
            assertTrue(
                captured.messages
                    .last()
                    .content
                    .text() == FidelitySentinels.USER_TEXT,
                "今回の入力が履歴より後ろに来ていません（時系列が崩れている）。",
            )
        }
    }

    @Test
    fun `the user turn persisted for the next request holds only this turn's user content`() {
        val repositories = ApapRepositories()
        repositories.conversationRepository.save(
            Conversation(
                conversationId = FidelitySentinels.CONVERSATION,
                sessionId = FidelitySentinels.SESSION,
                tenantId = EngineFixture.TENANT,
            ),
        )
        withRecordedRequest(repositories = repositories, conversation = FidelitySentinels.CONVERSATION) { _ ->
            val turns =
                repositories.conversationRepository
                    .findById(FidelitySentinels.CONVERSATION, EngineFixture.TENANT)!!
                    .turns
            val userTurn = turns.first { it.role == TurnRole.USER }
            val recorded = userTurn.contentParts.text()
            // System Promptを丸ごとUSER turnとして書くと、次のターンで履歴として読み戻したときに
            // 「ユーザがシステムプロンプトを喋った」ことになる。届く先はProviderなので、
            // これはリクエスト忠実性の問題そのものである。
            assertTrue(
                FidelitySentinels.SYSTEM_TEXT !in recorded,
                "USER turnにSystem Promptが混入しています（次ターンの履歴が壊れる）: $recorded",
            )
            assertTrue(
                FidelitySentinels.ASSISTANT_TEXT !in recorded,
                "USER turnに過去のassistant発話が混入しています: $recorded",
            )
            assertTrue(
                FidelitySentinels.USER_TEXT in recorded && FidelitySentinels.FOLLOW_UP_TEXT in recorded,
                "今回のユーザ発話が記録されていません: $recorded",
            )
        }
    }

    @Test
    fun `memory injection reaches the adapter as system context`() {
        val repositories = ApapRepositories()
        repositories.memoryRepository.store(
            apap.domain.model.conversation.Memory(
                memoryId = "01ARZ3NDEKTSV4RRFFQ69G5FZ5",
                tenantId = EngineFixture.TENANT,
                scope = apap.domain.model.conversation.MemoryScope.TENANT,
                content = FidelitySentinels.MEMORY_CONTENT,
                embedding = FidelitySentinels.MEMORY_VECTOR,
                importance = 1.0,
                lastAccessedAt = Instant.EPOCH,
            ),
        )
        withRecordedRequest(
            repositories = repositories,
            // 既定のQueryEmbedderは常に空ベクトルを返し、Memoryは一切引かれない。
            // ホストが埋め込みを供給できて初めてFR-CTX-004は実行経路に乗る。
            queryEmbedding = { FidelitySentinels.MEMORY_VECTOR },
        ) { captured ->
            val injected = captured.messages.filter { FidelitySentinels.MEMORY_CONTENT in it.content.text() }
            assertTrue(
                injected.isNotEmpty(),
                "Memoryの注入内容がAdapterまで届いていません（届いた内容: " +
                    captured.messages.joinToString { "${it.role}=${it.content.text()}" } + "）",
            )
            assertEquals(
                TurnRole.SYSTEM,
                injected.single().role,
                "Memory注入はSYSTEM相当の文脈として届くこと（USERとして届くと利用者の発話に見える）。",
            )
        }
    }

    @Test
    fun `the schema correction note reaches the adapter on the retry, not only the flat input`() {
        // 1回目はスキーマ違反、2回目は適合。ADR-0011 決定5の是正指示が
        // messages側にも入っていなければ、Adapterから見て1回目と同じプロンプトの再送になる。
        val recorder = SchemaViolatingRecorder(EngineFixture.mock(FidelitySentinels.mockConfig()))
        val repositories = ApapRepositories()
        val fixture =
            EngineFixture.build(
                capabilityId = FidelitySentinels.CAPABILITY,
                plugins = mapOf("plugin-a" to recorder),
                repositories = repositories,
            )

        fixture.use {
            runBlocking {
                val modelId = EngineFixture.registerActive(fixture, FidelitySentinels.CAPABILITY)
                FidelitySentinels.assignAlias(fixture, modelId)
                fixture.engine.execute(FidelitySentinels.request())

                assertEquals(2, recorder.count(), "是正リトライが起きていません: ${recorder.all().size}回")
                val retried = recorder.all()[1]
                val inMessages = retried.messages.any { CORRECTION_MARKER in it.content.text() }
                assertTrue(
                    inMessages,
                    "是正指示がmessagesに入っていません。Adapterはmessagesを読んで変換するため、" +
                        "inputだけに足しても届かず、同一プロンプトの単純再送になります。届いたmessages: " +
                        retried.messages.joinToString { "${it.role}=${it.content.text()}" },
                )
                assertEquals(
                    retried.messages.flatMap { it.content },
                    retried.input,
                    "是正後にinputとmessagesの内容がずれています。",
                )
            }
        }
    }

    @Test
    fun `after falling back to the next provider the request is still complete`() {
        // 候補が切り替わるとContextManager.refitがProcessedPromptを組み直す。
        // F4ではここ（refit）とDefaultPromptEngineが**別々に**roleを落としていたため、
        // 1つ目の候補だけを見るテストでは片方しか見つからない。
        val failing =
            EngineFixture.mock(
                apap.adapter.mock.MockAdapterConfig(
                    supportedCapabilities = setOf(FidelitySentinels.CAPABILITY),
                    forcedErrorCategory = apap.domain.model.vo.AdapterErrorCategory.PROVIDER_UNAVAILABLE,
                ),
            )
        val recorder = RecordingAdapter(EngineFixture.mock(FidelitySentinels.mockConfig()))
        val fixture =
            EngineFixture.build(
                capabilityId = FidelitySentinels.CAPABILITY,
                plugins = mapOf("plugin-a" to failing, "plugin-b" to recorder),
            )

        fixture.use {
            runBlocking {
                val cap = FidelitySentinels.CAPABILITY
                EngineFixture.registerActive(fixture, cap, pluginId = "plugin-a", priority = 90)
                EngineFixture.registerActive(fixture, cap, pluginId = "plugin-b", priority = 10)
                // Aliasは1つのModelへ固定するとFallback先が候補から外れるため、ここでは使わない。
                fixture.engine.execute(FidelitySentinels.request(modelAlias = null))

                val captured = recorder.first()
                assertEquals(
                    listOf(TurnRole.SYSTEM, TurnRole.USER, TurnRole.ASSISTANT, TurnRole.USER),
                    captured.messages.map { it.role },
                    "Fallback後にroleが失われています: " +
                        captured.messages.joinToString { "${it.role}=${it.content.text()}" },
                )
                assertReachingFieldsArrived(captured)
            }
        }
    }

    @Test
    fun `routing constraints and preferences do not reach the adapter`() {
        // constraints/preferencesは`CanonicalRequest`にしか無く、公開APIにもGatewayにも入口が無い
        // （Routingの絞り込み条件として内部で組み立てられる）。E2Eでは値を注入できないため、
        // 変換そのもの（RequestMapper）に対して確かめる。将来AdapterRequestへ足された場合は
        // こことRequestFidelityContractTestの両方が落ちる。
        val canonical =
            apap.domain.model.execution.CanonicalRequest(
                requestId =
                    apap.domain.model.vo
                        .RequestId(FidelitySentinels.REQUEST_ID),
                tenantId = EngineFixture.TENANT,
                principal = FidelitySentinels.PRINCIPAL,
                capabilityId = FidelitySentinels.CAPABILITY,
                input = listOf(ContentPart.Text(FidelitySentinels.USER_TEXT)),
                constraints = FidelitySentinels.constraintsSentinel(),
                timeoutBudget = FidelitySentinels.TIMEOUT_BUDGET,
                traceId = FidelitySentinels.TRACE_ID,
            )
        val mapped =
            apap.execution.mapping.RequestMapper.map(
                prompt =
                    apap.domain.model.execution.ProcessedPrompt(
                        input = listOf(ContentPart.Text(FidelitySentinels.USER_TEXT)),
                    ),
                req = canonical,
                modelName = "model-plugin-a",
                authContext = apap.adapter.spi.AuthContext(),
                timeout = FidelitySentinels.TIMEOUT_BUDGET,
            )

        val dump = mapped.toString()
        val constraintsSentinel = RequestFidelityContract.probes.getValue("constraints").sentinel!!
        assertTrue(
            constraintsSentinel !in dump,
            "Routing条件（constraints）がAdapterRequestへ載っています: $dump",
        )
        assertTrue(
            "OptimizeFor" !in dump && "BALANCED" !in dump,
            "Routing条件（preferences）がAdapterRequestへ載っています: $dump",
        )
    }

    @Test
    fun `no credential value reaches the adapter request`() {
        // EngineFixture.mockのSecretAccessorが返す値。Adapterは自分で解決するが、
        // AdapterRequestへ載せてはならない（不変条件4）。
        withRecordedRequest { captured ->
            assertTrue(
                "secret" !in captured.toString(),
                "Credentialの実値がAdapterRequestに含まれています（不変条件4違反）: $captured",
            )
            assertTrue(
                captured.authContext.headers.isEmpty(),
                "AuthContextにヘッダが載っています。Credential由来の値が入っていないか確認すること: " +
                    "${captured.authContext}",
            )
        }
    }

    private fun assertReachingFieldsArrived(captured: AdapterRequest) {
        assertEquals(FidelitySentinels.CAPABILITY, captured.capabilityId, "capabilityIdが届いていません")

        assertEquals(FidelitySentinels.TEMPERATURE, captured.params.temperature, "params.temperatureが届いていません")
        assertEquals(FidelitySentinels.MAX_TOKENS, captured.params.maxTokens, "params.maxTokensが届いていません")
        assertEquals(FidelitySentinels.TOP_P, captured.params.topP, "params.topPが届いていません")
        assertEquals(
            listOf(FidelitySentinels.STOP_A, FidelitySentinels.STOP_B),
            captured.params.stop,
            "params.stopが届いていません",
        )
        assertEquals(FidelitySentinels.SEED, captured.params.seed, "params.seedが届いていません")

        val tool = captured.tools?.singleOrNull()
        assertNotNull(tool, "toolsが届いていません（FR-CAP-005）: ${captured.tools}")
        assertEquals(FidelitySentinels.TOOL_NAME, tool!!.name)
        assertEquals(FidelitySentinels.TOOL_DESCRIPTION, tool.description)
        assertEquals(FidelitySentinels.TOOL_SCHEMA, tool.parametersSchema)

        val toolResult = captured.toolResults.singleOrNull()
        assertNotNull(toolResult, "toolResultsが届いていません: ${captured.toolResults}")
        assertEquals(FidelitySentinels.TOOL_CALL_ID, toolResult!!.callId)
        assertEquals(FidelitySentinels.TOOL_RESULT_CONTENT, toolResult.content)

        assertEquals(FidelitySentinels.OUTPUT_SCHEMA, captured.outputSchema, "outputSchemaが届いていません")

        assertEquals(
            FidelitySentinels.TRACE_ID,
            captured.traceHeaders["traceparent"],
            "traceIdがtraceparentとして届いていません: ${captured.traceHeaders}",
        )

        // timeoutは全体予算そのものではなく残予算。経過分だけ短く、かつ極端に減っていないこと。
        assertTrue(
            captured.timeout <= FidelitySentinels.TIMEOUT_BUDGET,
            "timeoutが元の予算を超えています: ${captured.timeout}",
        )
        assertTrue(
            captured.timeout > FidelitySentinels.TIMEOUT_BUDGET.minusSeconds(TIMEOUT_SLACK_SECONDS),
            "timeoutが予算からかけ離れて短くなっています（残予算の計算が壊れている可能性）: ${captured.timeout}",
        )

        assertTrue(captured.input.isNotEmpty(), "inputが空です")
        assertEquals(
            captured.messages.flatMap { it.content },
            captured.input,
            "inputとmessagesの内容がずれています（片方だけを更新した箇所がある）。",
        )
    }

    private fun assertNoForbiddenValue(captured: AdapterRequest) {
        val dump = captured.toString()
        val leaked =
            RequestFidelityContract.probes
                .mapNotNull { (field, probe) -> probe.sentinel?.takeIf { it in dump }?.let { field to it } }
        assertTrue(
            leaked.isEmpty(),
            "Adapterへ到達してはならない値が含まれています: $leaked。" +
                "分類（RequestFidelityContract）を変えたのでなければ、これは漏洩です。全文: $dump",
        )
        // modelAliasは論理名。Adapterが受け取るのは解決後の物理名であること（不変条件3）。
        assertTrue(
            captured.modelName.isNotBlank() && captured.modelName != FidelitySentinels.ALIAS,
            "modelNameがAliasのままです（物理名へ解決されていない）: ${captured.modelName}",
        )
    }

    /**
     * 見張り値入りリクエストを本番の入口経由で1回流し、Adapterが受け取ったものを[assertions]へ渡す。
     *
     * @param streaming trueなら`executeStream`を最後まで収集する
     */
    @Suppress("LongParameterList")
    private fun withRecordedRequest(
        streaming: Boolean = false,
        repositories: ApapRepositories = ApapRepositories(),
        messages: List<InputMessage>? = null,
        conversation: apap.domain.model.vo.ConversationId? = null,
        queryEmbedding: (suspend (List<ContentPart>) -> List<Double>)? = null,
        assertions: (AdapterRequest) -> Unit,
    ) {
        val recorder = RecordingAdapter(EngineFixture.mock(FidelitySentinels.mockConfig()))
        val fixture =
            EngineFixture.build(
                capabilityId = FidelitySentinels.CAPABILITY,
                plugins = mapOf("plugin-a" to recorder),
                repositories = repositories,
            ) { queryEmbedding?.let { embed -> queryEmbedding(embed) } }
        val engine = fixture.engine

        fixture.use {
            runBlocking {
                val modelId = EngineFixture.registerActive(fixture, FidelitySentinels.CAPABILITY)
                FidelitySentinels.assignAlias(fixture, modelId)

                val request =
                    if (messages == null) {
                        FidelitySentinels.request(conversationId = conversation)
                    } else {
                        FidelitySentinels.request(messages = messages, conversationId = conversation)
                    }

                if (streaming) {
                    engine.executeStream(request).collect()
                } else {
                    engine.execute(request)
                }

                assertEquals(
                    1,
                    recorder.count(),
                    "Adapterの呼出回数が1回ではありません（リトライ/是正が起きている可能性）: ${recorder.all()}",
                )
                assertions(recorder.first())
            }
        }
    }

    private fun turn(
        seq: Int,
        role: TurnRole,
        text: String,
    ) = Turn(
        turnId = "turn-$seq",
        seq = seq,
        role = role,
        contentParts = listOf(ContentPart.Text(text)),
        createdAt = Instant.EPOCH,
    )

    private fun List<ContentPart>.text(): String = filterIsInstance<ContentPart.Text>().joinToString("") { it.text }

    /** 1回目だけスキーマ違反の応答を返す。是正リトライの2回目に何が届くかを見るため。 */
    private class SchemaViolatingRecorder(
        private val delegate: apap.adapter.spi.ProviderAdapter,
    ) : apap.adapter.spi.ProviderAdapter by delegate {
        private val seen = java.util.concurrent.CopyOnWriteArrayList<AdapterRequest>()

        fun count(): Int = seen.size

        fun all(): List<AdapterRequest> = seen.toList()

        override suspend fun execute(request: AdapterRequest): apap.adapter.spi.AdapterResponse {
            seen += request
            val text =
                if (seen.size == 1) {
                    """{"sentinel_answer":42}"""
                } else {
                    FidelitySentinels.SCHEMA_CONFORMING_OUTPUT
                }
            return delegate.execute(request).copy(output = listOf(apap.adapter.spi.TextContentPart(text)))
        }
    }

    private companion object {
        /** [apap.execution.mapping.RequestMapper.withCorrectionNote]が書き込む文言の一部。 */
        const val CORRECTION_MARKER = "violated the required output schema"

        const val HISTORY_USER = "sentinel-history-user-8d2f"
        const val HISTORY_ASSISTANT = "sentinel-history-assistant-4b6c"

        /** 実行にかかる時間の許容幅（残予算の下限判定用）。 */
        const val TIMEOUT_SLACK_SECONDS = 30L
    }
}
