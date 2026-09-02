package apap.gateway

import apap.api.ApapStreamChunk
import apap.api.ApapStreamChunkType
import apap.domain.model.vo.ContentPart
import apap.gateway.routes.writeSseStream
import apap.gateway.sse.SseEventName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.StringWriter

/**
 * 02_システム仕様.md 2.10 / 13_API設計.md 13.3: `heartbeat`は15秒毎。
 *
 * **仮想時間で検証する。** 実時間で15秒待つテストは遅いうえに、CIの負荷次第で
 * 揺れる（flaky）——`TokenBucketRateLimiterTest`で同じ問題を踏んでおり、
 * `libs.versions.toml`のkotlinx-coroutines-testのコメントにも記録されている。
 *
 * HTTP層越しではなくSSE書き出しの本体（`writeSseStream`）を直接呼ぶ。Ktorの
 * `respondTextWriter`は実ディスパッチャで動くため、そこを通すと仮想時間が効かない。
 * ここで確かめたいのは「チャンクが来ない間、設定した間隔でheartbeatを刻むか」であり、
 * それはこの関数の責務そのもの。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SseHeartbeatTest {
    @Test
    fun `heartbeat is emitted once per configured interval while no chunk arrives`() =
        runTest {
            val writer = StringWriter()
            // しばらく黙り続け、その後1チャンクだけ流して終わるストリーム。
            val silentThenOne: Flow<ApapStreamChunk> =
                flow {
                    delay(SILENT_SECONDS * MILLIS_PER_SECOND)
                    emit(
                        ApapStreamChunk(
                            type = ApapStreamChunkType.CONTENT_DELTA,
                            index = 0,
                            delta = ContentPart.Text("done"),
                        ),
                    )
                }

            writer.writeSseStream(silentThenOne, responseId = RESPONSE_ID, heartbeatSeconds = HEARTBEAT_SECONDS)

            val events = writer.toString().eventNames()
            val heartbeats = events.count { it == SseEventName.HEARTBEAT }
            // 50秒沈黙 / 15秒間隔 = 3回（t=15,30,45）。その後t=50でCONTENT_DELTAが1件。
            assertEquals(
                (SILENT_SECONDS / HEARTBEAT_SECONDS).toInt(),
                heartbeats,
                "expected one heartbeat per $HEARTBEAT_SECONDS s of silence over $SILENT_SECONDS s, got: $events",
            )
            assertEquals(SseEventName.CONTENT_DELTA, events.last(), "the real chunk must still be delivered")
        }

    @Test
    fun `no heartbeat is emitted when chunks keep arriving within the interval`() =
        runTest {
            val writer = StringWriter()
            val chatty: Flow<ApapStreamChunk> =
                flow {
                    repeat(CHUNK_COUNT) { index ->
                        // 間隔より短い沈黙ならheartbeatは挟まらない。
                        delay((HEARTBEAT_SECONDS - 1) * MILLIS_PER_SECOND)
                        emit(
                            ApapStreamChunk(
                                type = ApapStreamChunkType.CONTENT_DELTA,
                                index = index,
                                delta = ContentPart.Text("chunk$index"),
                            ),
                        )
                    }
                }

            writer.writeSseStream(chatty, responseId = RESPONSE_ID, heartbeatSeconds = HEARTBEAT_SECONDS)

            val events = writer.toString().eventNames()
            assertTrue(
                events.none { it == SseEventName.HEARTBEAT },
                "heartbeats must not be injected while chunks keep arriving in time: $events",
            )
            assertEquals(CHUNK_COUNT, events.size)
        }

    private fun String.eventNames(): List<String> =
        Regex("^event: (.+)$", RegexOption.MULTILINE).findAll(this).map { it.groupValues[1] }.toList()

    private companion object {
        const val HEARTBEAT_SECONDS = 15L
        // 間隔の整数倍にしない: 整数倍だと最後のタイムアウトとチャンク送出が
        // 同一の仮想時刻で並び、どちらが先かが決まらない（実際にこれで揺れた）。
        const val SILENT_SECONDS = 50L
        const val MILLIS_PER_SECOND = 1000L
        const val CHUNK_COUNT = 3
        const val RESPONSE_ID = "01ARZ3NDEKTSV4RRFFQ69G5FD0"
    }
}
