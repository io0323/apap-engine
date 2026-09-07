package apap.adapter.anthropic

import apap.adapter.spi.FinishReason
import apap.adapter.spi.GenerationParams
import apap.adapter.spi.TextContentPart
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
