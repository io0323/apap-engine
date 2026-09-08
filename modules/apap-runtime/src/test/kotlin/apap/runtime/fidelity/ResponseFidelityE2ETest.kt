package apap.runtime.fidelity

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.spi.AdapterChunk
import apap.adapter.spi.AdapterChunkType
import apap.adapter.spi.TextContentPart
import apap.api.ApapStreamChunk
import apap.api.ApapStreamChunkType
import apap.domain.model.vo.FinishReason
import apap.runtime.EngineFixture
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * 応答忠実性（Response Fidelity）。[RequestFidelityE2ETest]がリクエスト方向を見るのに対し、
 * こちらは**Providerから返った情報が利用側まで届くか**を見る。
 *
 * ## なぜ要るか
 *
 * 非Streamingでは`finishReason`がGateway DTOまで届いていたのに、Streamingでは
 * `StreamChunk`/`ApapStreamChunk`にフィールドすら無く、**`length_limit`で切られたストリームが
 * 正常完了と区別できなかった**（13_API設計.md 13.3のSSE例は`message_end`に`finish_reason`を
 * 載せているのに、である）。P16で是正し、ADR-0028をSupersedeした。
 *
 * 「非Streamingで届く情報がStreamingでも届いているか」を一通り確認する検査でもある。
 */
class ResponseFidelityE2ETest {
    /**
     * 6値のうち、**Providerが応答として申告できる4値**をストリーム経路で確認する。
     * 残り2値の扱いは[the two finish reasons that cannot arrive as a terminal chunk]に書いた。
     */
    @TestFactory
    fun `every provider-declared finish reason reaches the caller through the stream`(): List<DynamicTest> =
        listOf(
            FinishReason.COMPLETED,
            FinishReason.LENGTH_LIMIT,
            FinishReason.TOOL_CALL,
            FinishReason.CONTENT_FILTERED,
        ).map { declared ->
            DynamicTest.dynamicTest("finishReason=$declared") {
                val chunks = streamWith(declared)
                val terminal = chunks.last { it.type == ApapStreamChunkType.MESSAGE_END }
                assertEquals(
                    declared,
                    terminal.finishReason,
                    "Adapterが申告した終了理由がストリーム経路で失われています。届いた: ${terminal.finishReason}",
                )
            }
        }

    @Test
    fun `the stream ends with exactly one terminal chunk`() {
        val chunks = streamWith(FinishReason.COMPLETED)
        assertEquals(
            1,
            chunks.count { it.type == ApapStreamChunkType.MESSAGE_END },
            "MESSAGE_ENDが複数あります。Adapter由来と実行エンジン由来の二重送出: ${chunks.map { it.type }}",
        )
    }

    /**
     * Adapterが終了理由を申告しなかった場合。**嘘をつかず、かつ欠落もさせない**——
     * 申告が無いストリームは正常完了とみなすのが13.3の既定（`{"finish_reason":"completed"}`）。
     */
    @Test
    fun `a stream whose adapter declares nothing still reports completed`() {
        val chunks = streamWith(declared = null)
        val terminal = chunks.last { it.type == ApapStreamChunkType.MESSAGE_END }
        assertEquals(FinishReason.COMPLETED, terminal.finishReason)
    }

    /**
     * 6値のうち残り2つ。**届かないことが正しい**が、その理由を記録しておく——
     * 「検証していない」と「構造上届かない」を後から区別できるようにするため。
     */
    @Test
    fun `the two finish reasons that cannot arrive as a terminal chunk`() {
        // CANCELLED: 利用側が切断した結果なので、そもそも受け取る相手がいない。
        // ERROR: 13.3「異常時は event: error で終端」に従い、MESSAGE_ENDではなくERRORチャンクで表す。
        val chunks = streamWith(FinishReason.COMPLETED)
        assertTrue(
            chunks.none { it.type == ApapStreamChunkType.ERROR },
            "正常系にERRORチャンクが混ざっています",
        )
        assertEquals(
            ApapStreamChunkType.MESSAGE_END,
            chunks.last().type,
            "終端がMESSAGE_ENDではありません: ${chunks.map { it.type }}",
        )
    }

    /** 非Streamingで届く情報がStreamingでも届くか、の突き合わせ。 */
    @Test
    fun `usage and finish reason both reach the caller on the streaming path`() {
        val chunks = streamWith(FinishReason.LENGTH_LIMIT)
        val usage = chunks.firstOrNull { it.type == ApapStreamChunkType.USAGE }?.usage
        assertNotNull(usage, "非Streamingでは届くusageがStreamingで欠落しています")
        val terminal = chunks.last { it.type == ApapStreamChunkType.MESSAGE_END }
        assertEquals(FinishReason.LENGTH_LIMIT, terminal.finishReason)
    }

    private fun streamWith(declared: FinishReason?): List<ApapStreamChunk> {
        val capabilityId = FidelitySentinels.CAPABILITY
        val fixture =
            EngineFixture.build(
                capabilityId,
                mapOf(
                    "plugin-a" to
                        EngineFixture.mock(
                            MockAdapterConfig(
                                supportedCapabilities = setOf(capabilityId),
                                streamChunks = chunksFor(declared),
                            ),
                        ),
                ),
            )
        return fixture.use {
            runBlocking {
                EngineFixture.registerActive(fixture, capabilityId)
                fixture.engine.executeStream(EngineFixture.request(capabilityId)).toList()
            }
        }
    }

    private fun chunksFor(declared: FinishReason?): List<AdapterChunk> =
        listOf(
            AdapterChunk(type = AdapterChunkType.MESSAGE_START, index = 0),
            AdapterChunk(type = AdapterChunkType.CONTENT_DELTA, index = 1, delta = TextContentPart("hi")),
            AdapterChunk(
                type = AdapterChunkType.USAGE,
                index = 2,
                usage =
                    apap.domain.model.vo.Usage.of(
                        apap.domain.model.vo
                            .TokenCount(3),
                        apap.domain.model.vo
                            .TokenCount(2),
                    ),
            ),
            AdapterChunk(type = AdapterChunkType.MESSAGE_END, index = 3, finishReason = declared),
        )
}
