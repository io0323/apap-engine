package apap.runtime

import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.AdapterResponse
import apap.adapter.spi.ProviderAdapter
import apap.domain.model.conversation.TurnRole
import apap.domain.model.execution.InputMessage
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * P11-F4 / ADR-0031: `messages[].role` がAdapterまで保たれることを検証する。
 *
 * ## 修正前に何が起きていたか
 *
 * roleは3箇所で失われていた。
 *
 * 1. Gatewayの `toApapRequest` が `messages.flatMap { it.content }` で平坦化
 * 2. `ExecutionEngine.buildContextualPrompt` が `assembled.turns.flatMap { it.contentParts }` で
 *    履歴の発話者を捨て、さらに `systemPrompt = emptyList()` をハードコードしていた
 * 3. `AdapterRequest.input` が `ContentPart[]` で、SPIとしてroleを表現できなかった
 *
 * 結果、System Promptは供給経路そのものが無く、マルチターン履歴は
 * 「誰の発話か分からない平坦な連結」としてProviderへ渡っていた。
 * 429のように騒がしく失敗せず、**静かに応答品質が劣化する**種類の欠陥である。
 *
 * このテストはAdapterが受け取った [AdapterRequest.messages] を直接検査する。
 * 「roleを受け取れる型がある」ことと「roleが実際に届く」ことは別なので、
 * 型の存在ではなく到達内容を見る。
 */
class MessageRoleE2ETest {
    private val capabilityId = CapabilityId("chat")

    @Test
    fun `system, user and assistant roles all reach the adapter distinctly`() {
        val seen = AtomicReference<List<InputMessage>>(emptyList())
        val fixture =
            EngineFixture.build(
                capabilityId,
                mapOf("plugin-a" to RecordingAdapter(EngineFixture.mock(mockConfig()), seen)),
            )

        fixture.use {
            runBlocking {
                EngineFixture.registerActive(fixture, capabilityId)

                fixture.engine.execute(
                    EngineFixture.request(capabilityId).copy(
                        messages =
                            listOf(
                                InputMessage(TurnRole.SYSTEM, listOf(ContentPart.Text("you are terse"))),
                                InputMessage(TurnRole.USER, listOf(ContentPart.Text("first question"))),
                                InputMessage(TurnRole.ASSISTANT, listOf(ContentPart.Text("first answer"))),
                                InputMessage(TurnRole.USER, listOf(ContentPart.Text("follow up"))),
                            ),
                    ),
                )

                val delivered = seen.get()
                assertTrue(delivered.isNotEmpty(), "Adapterへmessagesが届いていません（roleが失われている）。")

                assertEquals(
                    listOf(TurnRole.SYSTEM, TurnRole.USER, TurnRole.ASSISTANT, TurnRole.USER),
                    delivered.map { it.role },
                    "roleの並びが保たれていません。届いた内容: " +
                        delivered.joinToString { "${it.role}=${it.content.text()}" },
                )

                // System Promptがsystemとして届いていること（以前は供給経路が無かった）。
                val system = delivered.single { it.role == TurnRole.SYSTEM }
                assertEquals("you are terse", system.content.text())

                // 発話者の帰属が保たれていること——USERの2発話がASSISTANTの応答と混ざらない。
                assertEquals(
                    listOf("first question", "follow up"),
                    delivered.filter { it.role == TurnRole.USER }.map { it.content.text() },
                )
                assertEquals(
                    listOf("first answer"),
                    delivered.filter { it.role == TurnRole.ASSISTANT }.map { it.content.text() },
                )
            }
        }
    }

    @Test
    fun `a request built from plain input is still delivered as a single user message`() {
        val seen = AtomicReference<List<InputMessage>>(emptyList())
        val fixture =
            EngineFixture.build(
                capabilityId,
                mapOf("plugin-a" to RecordingAdapter(EngineFixture.mock(mockConfig()), seen)),
            )

        fixture.use {
            runBlocking {
                EngineFixture.registerActive(fixture, capabilityId)
                // role指定のない旧経路（input のみ）。移行期の互換（ADR-0031 決定2）。
                fixture.engine.execute(EngineFixture.request(capabilityId, text = "plain"))

                assertEquals(
                    listOf(TurnRole.USER),
                    seen.get().map { it.role },
                    "role未指定の入力はUSER単独として扱うこと（SYSTEM等へ昇格させない）。",
                )
                assertEquals(
                    "plain",
                    seen
                        .get()
                        .single()
                        .content
                        .text(),
                )
            }
        }
    }

    private fun List<ContentPart>.text(): String = filterIsInstance<ContentPart.Text>().joinToString("") { it.text }

    private fun mockConfig() = apap.adapter.mock.MockAdapterConfig(supportedCapabilities = setOf(capabilityId))

    /** Adapterが実際に受け取った[AdapterRequest.messages]を記録する。 */
    private class RecordingAdapter(
        private val delegate: ProviderAdapter,
        private val seen: AtomicReference<List<InputMessage>>,
    ) : ProviderAdapter by delegate {
        override suspend fun execute(request: AdapterRequest): AdapterResponse {
            seen.set(request.messages)
            return delegate.execute(request)
        }
    }
}
