package apap.runtime

import apap.adapter.mock.MockAdapterConfig
import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.AdapterResponse
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.TextContentPart
import apap.api.ApapException
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * P11-F3 / FR-CAP-003 / FR-PMT-002: スキーマ検証が**本番配線で実際に走る**ことを検証する。
 *
 * ## P11時点で何が動いていなかったか
 *
 * - `CapabilityRegistry`（json-schema-validator委譲）がどこからも構築されておらず、
 *   P7で自前バリデータから差し替えた作業が実行経路に乗っていなかった
 * - `PromptValidator`はPipelineに入っていたが、送出する`PromptValidationFailedException`が
 *   `FailureNormalization`の分岐に無く`else -> this`へ落ちていた。結果、
 *   apap-prompt（implementationスコープ）の例外型がそのまま出てホストは型で捕捉できず、
 *   Gatewayからは500 INTERNAL_ERRORに見えていた——実際は利用側の入力誤りで400が正しい
 * - リクエスト単位の`outputSchema`を検証する箇所が存在せず、
 *   スキーマ指定に従っていない応答が正常応答として返っていた（是正リトライの発火源が無かった）
 */
class SchemaValidationE2ETest {
    private val capabilityId = CapabilityId("chat")

    /** `{"answer": <string>}` を要求するスキーマ。 */
    private val outputSchema =
        """{"type":"object","required":["answer"],"properties":{"answer":{"type":"string"}}}"""

    @Test
    fun `FR-PMT-002 - an invalid prompt is rejected as PROMPT_VALIDATION_FAILED, not as an opaque failure`() {
        val fixture = EngineFixture.buildWithMock(capabilityId)

        fixture.use {
            runBlocking {
                EngineFixture.registerActive(fixture, capabilityId)

                // PromptValidatorのサイズ上限を超える入力（FR-PMT-002）。
                val huge = "a".repeat(OVERSIZED_CHARS)
                val failure =
                    assertThrows(ApapException::class.java) {
                        runBlocking { fixture.engine.execute(EngineFixture.request(capabilityId, text = huge)) }
                    }

                assertEquals(
                    ErrorCode.PROMPT_VALIDATION_FAILED,
                    failure.code,
                    "入力検証の失敗が13.4のPROMPT_VALIDATION_FAILEDとして返っていません（実際: ${failure.code}）。" +
                        "内部例外がそのまま漏れると、ホストは型でもコードでも判別できません。",
                )
            }
        }
    }

    @Test
    fun `FR-CAP-003 - a schema violating response is corrected on retry and then succeeds`() {
        val attempts = AtomicInteger(0)
        // 1回目はスキーマ違反、2回目は適合。ADR-0011の是正リトライが働けば成功で終わる。
        val fixture =
            EngineFixture.build(
                capabilityId,
                mapOf(
                    "plugin-a" to
                        ScriptedTextAdapter(
                            EngineFixture.mock(MockAdapterConfig(supportedCapabilities = setOf(capabilityId))),
                            attempts,
                            listOf("""{"answer": 42}""", """{"answer":"ok"}"""),
                        ),
                ),
            )

        fixture.use {
            runBlocking {
                EngineFixture.registerActive(fixture, capabilityId)

                val response =
                    fixture.engine.execute(
                        EngineFixture.request(capabilityId).copy(outputSchema = outputSchema),
                    )

                assertEquals(
                    2,
                    attempts.get(),
                    "スキーマ違反の応答が是正リトライされていません（Adapter呼出回数=${attempts.get()}）。",
                )
                assertTrue(
                    response.output.isNotEmpty(),
                    "是正後の応答が返っていません: ${response.finishReason}",
                )
            }
        }
    }

    @Test
    fun `FR-CAP-003 - corrections are capped per request and the violation surfaces when exhausted`() {
        val attempts = AtomicInteger(0)
        // 常にスキーマ違反を返す。ADR-0011: 是正はリクエスト全体で最大2回。
        val fixture =
            EngineFixture.build(
                capabilityId,
                mapOf(
                    "plugin-a" to
                        ScriptedTextAdapter(
                            EngineFixture.mock(MockAdapterConfig(supportedCapabilities = setOf(capabilityId))),
                            attempts,
                            listOf("""{"answer": 1}"""),
                        ),
                ),
            )

        fixture.use {
            runBlocking {
                EngineFixture.registerActive(fixture, capabilityId)

                val failure =
                    assertThrows(ApapException::class.java) {
                        runBlocking {
                            fixture.engine.execute(
                                EngineFixture.request(capabilityId).copy(outputSchema = outputSchema),
                            )
                        }
                    }

                // 是正枠（既定2回）を使い切ったら、通さずに違反として返すこと。
                assertTrue(
                    attempts.get() in MIN_ATTEMPTS..MAX_ATTEMPTS,
                    "是正回数がADR-0011の枠（リクエスト全体で最大2回）と整合しません: ${attempts.get()}回",
                )
                assertTrue(
                    failure.message.orEmpty().contains("schema") ||
                        failure.code == ErrorCode.PROVIDER_ERROR,
                    "スキーマ違反が最終的にエラーとして表面化していません: ${failure.code} / ${failure.message}",
                )
            }
        }
    }

    /** 呼び出し回数に応じて決まったテキストを返すAdapter。最後の要素以降は同じものを返す。 */
    private class ScriptedTextAdapter(
        private val delegate: ProviderAdapter,
        private val attempts: AtomicInteger,
        private val texts: List<String>,
    ) : ProviderAdapter by delegate {
        override suspend fun execute(request: AdapterRequest): AdapterResponse {
            val index = attempts.getAndIncrement()
            val base = delegate.execute(request)
            return base.copy(output = listOf(TextContentPart(texts[minOf(index, texts.lastIndex)])))
        }
    }

    private companion object {
        /** `PromptValidator`の既定サイズ上限を確実に超える長さ。 */
        const val OVERSIZED_CHARS = 200_000

        /** 初回 + 是正2回。実装のリトライ予算次第で前後しうるため幅で見る。 */
        const val MIN_ATTEMPTS = 2
        const val MAX_ATTEMPTS = 3
    }
}
