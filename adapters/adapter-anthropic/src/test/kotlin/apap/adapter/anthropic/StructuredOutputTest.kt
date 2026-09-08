package apap.adapter.anthropic

import apap.adapter.spi.FinishReason
import apap.adapter.spi.GenerationParams
import apap.adapter.spi.InputMessage
import apap.adapter.spi.TextContentPart
import apap.adapter.spi.TurnRole
import apap.domain.model.vo.AdapterErrorCategory
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR-0040の最優先項目: `outputSchema` がProviderへ**実際に渡る**ことの検証。
 *
 * ## 何が問題だったか
 *
 * P15時点では `outputSchema` はAdapterのmain配下で**参照ゼロ**だった。スキーマが渡らないため
 * モデルは構造の指示なしに生成し、毎回まず`AttemptExecutor`の検証に落ちてから
 * ADR-0011の是正リトライで直る動作になっていた。是正機構は例外的救済であって常用経路ではなく、
 * コスト（往復2回）と成功率の両方に効く。
 *
 * P14のリクエスト忠実性検査は「Adapterへ**届く**こと」を検証したが、
 * 「Adapterが**使う**こと」は別問題だった、という指摘そのものの是正である。
 */
class StructuredOutputTest {
    private val mapper = ObjectMapper()

    private val schema =
        """{"type":"object","required":["answer"],"properties":{"answer":{"type":"string"}}}"""

    @Test
    fun `the schema reaches the provider through the native structured output mechanism`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        runBlocking {
            adapter.execute(requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(outputSchema = schema))
        }

        val body = mapper.readTree(transport.calls.single().body)
        val format = body.path("output_config").path("format")
        assertFalse(format.isMissingNode, "output_config.format が送られていません: $body")
        assertEquals("json_schema", format.path("type").asText())
        assertEquals(
            "string",
            format
                .path("schema")
                .path("properties")
                .path("answer")
                .path("type")
                .asText(),
            "スキーマ本体が欠けています: $format",
        )
    }

    @Test
    fun `the prompt mode embeds the schema into the system prompt instead`() {
        val transport = ScenarioTransport()
        val adapter = adapterWithOptions(transport, mapOf(StructuredOutputMode.OPTION_KEY to "prompt"))
        runBlocking {
            adapter.execute(requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(outputSchema = schema))
        }

        val body = mapper.readTree(transport.calls.single().body)
        assertTrue(body.path("output_config").isMissingNode, "promptモードでネイティブ機構を使っています")
        assertTrue(
            body.path("system").asText().contains("\"answer\""),
            "systemプロンプトにスキーマが組み込まれていません: ${body.path("system").asText()}",
        )
    }

    /**
     * 効果の測定。スキーマを渡さないProviderでは初回応答が検証に落ちるが、渡せば落ちない。
     * 記録ベースなので「モデルが実際に従うか」ではなく「**指示が届いているか**」を測る——
     * 実APIでの初回成功率そのものは実測が要る（findings の [要実測]）。
     */
    @Test
    fun `with the schema delivered, the first response already conforms`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        val response =
            runBlocking {
                adapter.execute(
                    requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(
                        outputSchema = schema,
                        messages = listOf(userMessage(ScenarioTransport.SCHEMA_AWARE_MARKER)),
                        input = listOf(TextContentPart(ScenarioTransport.SCHEMA_AWARE_MARKER)),
                    ),
                )
            }
        // 1回の呼出で終わっている＝是正リトライを必要としていない。
        assertEquals(1, transport.calls.size, "是正リトライが発生しています")
        val text = response.output.filterIsInstance<TextContentPart>().joinToString("") { it.text }
        assertNotNull(mapper.readTree(text).path("answer").asText(), "スキーマに適合した応答ではありません: $text")
        assertEquals(FinishReason.COMPLETED, response.finishReason)
    }

    @Test
    fun `OFF mode is available only for comparison and does not send the schema`() {
        val transport = ScenarioTransport()
        val adapter = adapterWithOptions(transport, mapOf(StructuredOutputMode.OPTION_KEY to "off"))
        runBlocking {
            adapter.execute(requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(outputSchema = schema))
        }
        val body = mapper.readTree(transport.calls.single().body)
        assertTrue(body.path("output_config").isMissingNode)
        assertTrue(body.path("system").isMissingNode, "offモードなのにsystemへ混入しています")
    }

    @Test
    fun `an unknown structured output mode fails loudly instead of falling back`() {
        val transport = ScenarioTransport()
        val adapter = adapterWithOptions(transport, mapOf(StructuredOutputMode.OPTION_KEY to "typo"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                adapter.execute(requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(outputSchema = schema))
            }
        }
    }

    /** ADR-0040: 非対応パラメタを黙って捨てない。 */
    @Test
    fun `seed is rejected explicitly rather than silently dropped`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        val thrown =
            assertThrows(apap.adapter.spi.AdapterException::class.java) {
                runBlocking {
                    adapter.execute(
                        requestFor(AnthropicAdapter.CAPABILITY_CHAT)
                            .copy(params = GenerationParams(seed = 42L)),
                    )
                }
            }
        assertEquals(AdapterErrorCategory.UNSUPPORTED_CAPABILITY, thrown.category)
        assertTrue(
            thrown.message.orEmpty().contains("seed"),
            "どのパラメタが非対応なのかが分かりません: ${thrown.message}",
        )
        assertTrue(transport.calls.isEmpty(), "非対応と分かっているのにProviderを呼んでいます")
    }

    /** ADR-0040: Model側の上限がAdapterへ届き、既定値の捏造より優先されること。 */
    @Test
    fun `the model output limit is used when the request does not specify max tokens`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        runBlocking {
            adapter.execute(requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(modelMaxOutputTokens = 8192))
        }
        assertEquals(
            8192,
            mapper.readTree(transport.calls.single().body).path("max_tokens").asInt(),
            "Modelの上限ではなくAdapterの既定値が使われています",
        )
    }

    /**
     * 構造化出力は正式機能であり、**ベータヘッダを要さない**（出典:
     * [StructuredOutputSchema.DOC_SOURCE]）。付けたままにすると、ヘッダが廃止された時点で
     * 400になる時限爆弾になるため、送っていないことを検査で固定する。
     */
    @Test
    fun `no beta header is attached to a structured output request`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        runBlocking {
            adapter.execute(requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(outputSchema = schema))
        }

        val headerNames =
            transport.calls
                .single()
                .headers.keys
                .map { it.lowercase() }
        assertTrue(
            headerNames.none { it.contains("beta") },
            "不要なベータヘッダを送っています: $headerNames",
        )
    }

    /** スキーマは実APIが受け付ける形へ整えてから載せる（[StructuredOutputSchema]）。 */
    @Test
    fun `the schema is preprocessed before it is put on the wire`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        runBlocking {
            adapter.execute(
                requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(
                    outputSchema =
                        """
                        {"type":"object","required":["age"],
                         "properties":{"age":{"type":"integer","minimum":0,"maximum":130}}}
                        """.trimIndent(),
                ),
            )
        }

        val sent = mapper.readTree(transport.calls.single().body).at("/output_config/format/schema")
        assertEquals(false, sent.path("additionalProperties").asBoolean(true), "additionalProperties:falseが要ります")
        assertTrue(sent.at("/properties/age/minimum").isMissingNode, "サポート外の制約が残っています: $sent")
        assertTrue(
            sent.at("/properties/age/description").asText().contains("Must be at least 0."),
            "外した制約が指示として残っていません: $sent",
        )
    }

    /** Streamingとは併用できる。片方だけ動く実装になっていないことを見る。 */
    @Test
    fun `structured output is also sent on the streaming path`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        runBlocking {
            adapter.executeStream(requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(outputSchema = schema))
        }

        val body = mapper.readTree(transport.calls.single().body)
        assertTrue(body.path("stream").asBoolean(false))
        assertEquals("json_schema", body.at("/output_config/format/type").asText())
    }

    /**
     * Message Prefilling（末尾のassistantメッセージ）は`output_config.format`と併用できず、
     * Providerでは400になる。SPIに「prefill」という概念が無いぶん利用側からは気付けないので、
     * 送る前に理由の分かる失敗にする。
     */
    @Test
    fun `prefilling combined with native structured output fails locally with a usable message`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        val thrown =
            assertThrows(apap.adapter.spi.AdapterException::class.java) {
                runBlocking {
                    adapter.execute(
                        requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(
                            outputSchema = schema,
                            messages =
                                listOf(
                                    userMessage("hello"),
                                    InputMessage(TurnRole.ASSISTANT, listOf(TextContentPart("{"))),
                                ),
                        ),
                    )
                }
            }

        // 再試行しても直らない組み合わせなので INVALID_REQUEST（2.11でRetry対象外）。
        assertEquals(AdapterErrorCategory.INVALID_REQUEST, thrown.category)
        assertTrue(
            thrown.message.orEmpty().contains(StructuredOutputMode.OPTION_KEY),
            "退避先（promptモード）が示されていません: ${thrown.message}",
        )
        assertTrue(transport.calls.isEmpty(), "400になると分かっているのにProviderを呼んでいます")
    }

    /** 併用不可なのはNATIVEのときだけ。退避経路（promptモード）では通ること。 */
    @Test
    fun `the prompt fallback still accepts a prefilled conversation`() {
        val transport = ScenarioTransport()
        val adapter = adapterWithOptions(transport, mapOf(StructuredOutputMode.OPTION_KEY to "prompt"))
        runBlocking {
            adapter.execute(
                requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(
                    outputSchema = schema,
                    messages =
                        listOf(
                            userMessage("hello"),
                            InputMessage(TurnRole.ASSISTANT, listOf(TextContentPart("{"))),
                        ),
                ),
            )
        }
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `an explicit request value still wins over the model limit`() {
        val transport = ScenarioTransport()
        val adapter = initializedAdapter(transport)
        runBlocking {
            adapter.execute(
                requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(
                    params = GenerationParams(maxTokens = 64),
                    modelMaxOutputTokens = 8192,
                ),
            )
        }
        assertEquals(64, mapper.readTree(transport.calls.single().body).path("max_tokens").asInt())
    }
}
