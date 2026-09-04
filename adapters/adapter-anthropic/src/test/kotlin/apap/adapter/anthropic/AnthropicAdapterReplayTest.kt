package apap.adapter.anthropic

import apap.adapter.spi.AdapterChunkType
import apap.adapter.spi.AdapterException
import apap.adapter.spi.AudioContentPart
import apap.adapter.spi.FinishReason
import apap.adapter.spi.InputMessage
import apap.adapter.spi.TextContentPart
import apap.adapter.spi.TurnRole
import apap.domain.model.vo.AdapterErrorCategory
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 記録データに対する再生テスト。Contract Testが見ない「変換の中身」を検証する。
 *
 * 検証の向きは apap-runtime の RequestFidelityE2ETest と同じで、
 * **Adapterが何を送り、何を返したか**を見る。応答が返ったことだけを見ると、
 * system の巻き上げやrole交互化のような写像の誤りが素通りする。
 */
class AnthropicAdapterReplayTest {
    private val mapper = ObjectMapper()

    @Test
    fun `system messages are hoisted out of messages into the top-level system parameter`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        runBlocking {
            adapter.execute(
                requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(
                    messages =
                        listOf(
                            InputMessage(TurnRole.SYSTEM, listOf(TextContentPart("be terse"))),
                            InputMessage(TurnRole.USER, listOf(TextContentPart("hi"))),
                        ),
                ),
            )
        }
        val body = mapper.readTree(transport.calls.single().body)
        assertEquals("be terse", body.path("system").asText(), "system がトップレベルへ巻き上がっていません: $body")
        assertEquals(1, body.path("messages").size(), "system が messages に残っています: $body")
        assertEquals("user", body.path("messages")[0].path("role").asText())
    }

    @Test
    fun `consecutive same-role messages are merged and a leading assistant turn is prefixed`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        runBlocking {
            adapter.execute(
                requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(
                    messages =
                        listOf(
                            InputMessage(TurnRole.ASSISTANT, listOf(TextContentPart("earlier answer"))),
                            InputMessage(TurnRole.USER, listOf(TextContentPart("a"))),
                            InputMessage(TurnRole.USER, listOf(TextContentPart("b"))),
                        ),
                ),
            )
        }
        val roles = mapper.readTree(transport.calls.single().body).path("messages").map { it.path("role").asText() }
        // 先頭にuserが補われ、連続したuser 2件が1件へマージされる。
        assertEquals(listOf("user", "assistant", "user"), roles, "role の交互化が効いていません: $roles")
    }

    @Test
    fun `tool results are folded into a user message as tool_result blocks`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        runBlocking {
            adapter.execute(
                requestFor(AnthropicAdapter.CAPABILITY_TOOL_CALLING).copy(
                    toolResults = listOf(apap.adapter.spi.ToolResult("toolu_x", "sunny", isError = false)),
                ),
            )
        }
        val messages = mapper.readTree(transport.calls.single().body).path("messages")
        val last = messages[messages.size() - 1]
        assertEquals("user", last.path("role").asText())
        val block = last.path("content")[0]
        assertEquals("tool_result", block.path("type").asText())
        assertEquals("toolu_x", block.path("tool_use_id").asText())
        assertEquals("sunny", block.path("content").asText())
    }

    @Test
    fun `tool calls in a non-streaming response become ToolCall entries`() {
        val transport = RecordedTransport(ReplayHttpTransport.load("chat-tool-use"))
        val adapter = initializedAdapter(transport)
        val response = runBlocking { adapter.execute(requestFor(AnthropicAdapter.CAPABILITY_TOOL_CALLING)) }

        assertEquals(FinishReason.TOOL_CALL, response.finishReason)
        val call = response.toolCalls.single()
        assertEquals("toolu_redacted_01", call.callId)
        assertEquals("lookup", call.toolName)
        assertEquals("weather", mapper.readTree(call.arguments).path("query").asText())
    }

    @Test
    fun `a refusal arrives as a successful response with CONTENT_FILTERED, not as an exception`() {
        val transport = RecordedTransport(ReplayHttpTransport.load("chat-refusal"))
        val adapter = initializedAdapter(transport)
        val response = runBlocking { adapter.execute(requestFor(AnthropicAdapter.CAPABILITY_CHAT)) }
        // これがSPIの非対称性そのもの: 8分類にCONTENT_FILTEREDがあるのに、実APIは200で返す。
        assertEquals(FinishReason.CONTENT_FILTERED, response.finishReason)
    }

    @Test
    fun `streaming text is delivered as content deltas followed by usage and message end`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        val chunks = runBlocking { drain(adapter.executeStream(requestFor(AnthropicAdapter.CAPABILITY_STREAMING))) }

        val types = chunks.map { it.type }
        assertEquals(AdapterChunkType.MESSAGE_START, types.first())
        assertEquals(AdapterChunkType.MESSAGE_END, types.last())
        assertTrue(AdapterChunkType.HEARTBEAT in types, "ping が HEARTBEAT へ写っていません: $types")
        val text =
            chunks
                .filter { it.type == AdapterChunkType.CONTENT_DELTA }
                .mapNotNull { (it.delta as? TextContentPart)?.text }
                .joinToString("")
        assertEquals("Hello", text)

        val usage = chunks.single { it.type == AdapterChunkType.USAGE }.usage!!
        assertEquals(11, usage.inputTokens.value, "message_start の input_tokens が拾えていません")
        assertEquals(9, usage.outputTokens.value, "message_delta の output_tokens が拾えていません")
        assertFalse(usage.estimated, "Provider実測値なので estimated=false であるべき")
    }

    /**
     * ADR-0019の核心。`content_block_stop` を完了シグナルとして流せているか。
     * これが供給できないと、コア側はブレース数え上げで引数の完結を推測するしかない。
     */
    @Test
    fun `a streamed tool call ends with an explicit toolCallComplete signal`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        val request =
            requestFor(AnthropicAdapter.CAPABILITY_TOOL_CALLING).copy(
                messages = listOf(userMessage(ScenarioTransport.STREAM_TOOL_MARKER)),
                input = listOf(TextContentPart(ScenarioTransport.STREAM_TOOL_MARKER)),
            )
        val chunks = runBlocking { drain(adapter.executeStream(request)) }

        val toolChunks = chunks.filter { it.type == AdapterChunkType.TOOL_CALL_DELTA }
        assertTrue(toolChunks.isNotEmpty(), "tool_use のチャンクが出ていません")
        assertTrue(
            toolChunks.last().toolCallComplete,
            "content_block_stop を toolCallComplete=true として流せていません: $toolChunks",
        )
        assertEquals(
            1,
            toolChunks.count { it.toolCallComplete },
            "完了シグナルは1回だけであるべき: $toolChunks",
        )
        // 引数はデルタの連結で完全なJSONになる（コア側が組み立てられること）。
        val arguments = toolChunks.joinToString("") { it.toolCallDelta?.arguments.orEmpty() }
        assertEquals("weather", mapper.readTree(arguments).path("query").asText())
        assertEquals("toolu_redacted_02", toolChunks.first().toolCallDelta!!.callId)
    }

    @Test
    fun `an error event mid-stream is classified instead of ending the stream silently`() {
        val transport = RecordedTransport(ReplayHttpTransport.load("stream-midway-error"))
        val adapter = initializedAdapter(transport)
        val stream = runBlocking { adapter.executeStream(requestFor(AnthropicAdapter.CAPABILITY_STREAMING)) }

        val thrown =
            assertThrows(AdapterException::class.java) {
                runBlocking { drain(stream) }
            }
        assertEquals(AdapterErrorCategory.PROVIDER_UNAVAILABLE, thrown.category)
    }

    @Test
    fun `cancel stops the underlying provider connection`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        runBlocking {
            val stream = adapter.executeStream(requestFor(AnthropicAdapter.CAPABILITY_STREAMING))
            stream.next()
            stream.cancel()
            assertNull(stream.next(), "cancel 後もチャンクが流れています")
        }
        assertTrue(transport.lastStream!!.cancelled, "Provider側の接続が切られていません")
    }

    @Test
    fun `rate limit responses carry retry-after through to the exception`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        val request =
            requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(
                messages = listOf(userMessage("force-error:rate-limited")),
                input = listOf(TextContentPart("force-error:rate-limited")),
            )
        val thrown = assertThrows(AdapterException::class.java) { runBlocking { adapter.execute(request) } }
        assertEquals(AdapterErrorCategory.RATE_LIMITED, thrown.category)
        assertEquals(Duration.ofSeconds(7), thrown.retryAfter, "retry-after ヘッダが拾えていません")
    }

    @Test
    fun `discoverModels reads the model list endpoint`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        val models = runBlocking { adapter.discoverModels() }
        assertEquals(listOf("test-model-1", "test-model-2"), models.map { it.modelName })
        assertTrue(models.all { it.contextWindow > 0 }, "context window が埋まっていません")
    }

    @Test
    fun `the api key is sent as a header and never appears in the request body`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        runBlocking { adapter.execute(requestFor(AnthropicAdapter.CAPABILITY_CHAT)) }
        val call = transport.calls.single()
        assertEquals(TEST_SECRET, call.headers[AnthropicAdapter.API_KEY_HEADER])
        assertFalse(call.body!!.contains(TEST_SECRET), "Credential がリクエストボディに混入しています")
        // HttpCall.toString() が値を出さないこと（ログへ流れる経路を塞ぐ）。
        assertFalse(call.toString().contains(TEST_SECRET), "HttpCall.toString() が Credential を露出しています")
    }

    @Test
    fun `unsupported modalities are rejected as UNSUPPORTED_CAPABILITY rather than sent as broken json`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        val request =
            requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(
                messages =
                    listOf(
                        InputMessage(
                            TurnRole.USER,
                            listOf(AudioContentPart("https://example.invalid/a.wav", "audio/wav")),
                        ),
                    ),
            )
        val thrown = assertThrows(AdapterException::class.java) { runBlocking { adapter.execute(request) } }
        assertEquals(AdapterErrorCategory.UNSUPPORTED_CAPABILITY, thrown.category)
    }

    private suspend fun drain(stream: apap.adapter.spi.ProviderAdapter.AdapterStream) =
        buildList {
            while (true) {
                add(stream.next() ?: break)
            }
        }

    /** 単一の記録をそのまま返すtransport（シナリオ分岐の要らないケース用）。 */
    private class RecordedTransport(
        private val recording: ReplayHttpTransport.Recording,
    ) : HttpTransport {
        override suspend fun send(request: HttpCall): HttpReply = recording.reply!!

        override suspend fun openEventStream(request: HttpCall): EventStream = replayStream(recording.events)

        override fun close() = Unit
    }
}
