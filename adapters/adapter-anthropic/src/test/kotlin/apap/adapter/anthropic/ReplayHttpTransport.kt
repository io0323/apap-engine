package apap.adapter.anthropic

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * 記録済みのやりとりを返す[HttpTransport]。CIはこれで回す（実APIを叩かない）。
 *
 * ## なぜtransport層で差し替えるのか
 *
 * ここを差し替えると、Adapter本体（ヘッダ組立・ボディ生成・SSE解析・エラー分類）は
 * 実APIのときと**完全に同じ経路**を通る。HTTPクライアントの内側やAdapterのメソッド単位で
 * モックすると、再生時にAdapterの一部が迂回され「再生では通るが実APIでは落ちる」を作り込む。
 *
 * ## 記録データの出所（重要）
 *
 * `src/test/resources/recordings/` のフィクスチャは、**実APIから記録したものではない**。
 * 公開API仕様に基づいて手で書いたもので、各ファイルの `source` フィールドがそれを宣言する
 * （[RecordingProvenanceTest]が全ファイルにこの宣言があることを機械検証する）。
 * 実記録への差し替え手順は adapters/adapter-anthropic/README.md を参照。
 */
class ReplayHttpTransport(
    private val recording: Recording,
) : HttpTransport {
    /** 実際に届いたリクエスト。ヘッダ・ボディの検証に使う。 */
    val calls = mutableListOf<HttpCall>()

    var closed = false
        private set

    override suspend fun send(request: HttpCall): HttpReply {
        calls += request
        recording.transportFailure?.let { throw it }
        return recording.reply ?: error("recording ${recording.name} has no non-streaming reply")
    }

    override suspend fun openEventStream(request: HttpCall): EventStream {
        calls += request
        recording.transportFailure?.let { throw it }
        return ReplayEventStream(recording.events)
    }

    override fun close() {
        closed = true
    }

    /** [cancel]が呼ばれたかを検証できるようにしておく（SPIはcancelでの切断を要求する）。 */
    class ReplayEventStream(
        events: List<ServerSentEvent>,
    ) : EventStream {
        private val remaining = ArrayDeque(events)

        var cancelled = false
            private set

        var delivered = 0
            private set

        override suspend fun next(): ServerSentEvent? {
            if (cancelled) return null
            return remaining.removeFirstOrNull()?.also { delivered++ }
        }

        override fun cancel() {
            cancelled = true
            remaining.clear()
        }
    }

    /**
     * 1件の記録。非Streamingは[reply]、Streamingは[events]を使う。
     *
     * @param source この記録がどこから来たのか（実APIの記録か、仕様からの手書きか）
     */
    data class Recording(
        val name: String,
        val source: String,
        val reply: HttpReply? = null,
        val events: List<ServerSentEvent> = emptyList(),
        val transportFailure: Throwable? = null,
    )

    companion object {
        private val mapper = ObjectMapper()

        /** `src/test/resources/recordings/<name>.json` を読む。 */
        fun load(name: String): Recording {
            val path = "/recordings/$name.json"
            val text =
                ReplayHttpTransport::class.java
                    .getResourceAsStream(path)
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("記録データが見つかりません: $path")
            val node = mapper.readTree(text)
            val source = node.path("source").asText("")
            check(source.isNotBlank()) {
                "記録 $name に source がありません。実APIの記録か手書きかを宣言していない記録は使えません。"
            }
            val reply =
                node.path("reply").takeIf { !it.isMissingNode }?.let {
                    HttpReply(
                        status = it.path("status").asInt(),
                        headers =
                            it
                                .path("headers")
                                .properties()
                                .associate { (k, v) -> k.lowercase() to v.asText() },
                        body = mapper.writeValueAsString(it.path("body")),
                    )
                }
            val events =
                node.path("events").map {
                    ServerSentEvent(
                        event = it.path("event").asText(null),
                        data = mapper.writeValueAsString(it.path("data")),
                    )
                }
            return Recording(name = name, source = source, reply = reply, events = events)
        }

        /** 生の本文をそのまま返す記録（エラー応答など、JSONオブジェクトとして書けない場合に使う）。 */
        fun rawReply(
            name: String,
            status: Int,
            body: String,
            headers: Map<String, String> = emptyMap(),
        ): Recording =
            Recording(
                name = name,
                source = "hand-authored from public API documentation",
                reply = HttpReply(status, headers, body),
            )

        fun failing(
            name: String,
            cause: Throwable,
        ): Recording =
            Recording(
                name = name,
                source = "hand-authored (transport failure simulation)",
                transportFailure = cause,
            )

        fun timeoutMarker(): Duration = Duration.ofMillis(1)
    }
}

/** [ReplayHttpTransport.ReplayEventStream]の短縮生成（行長のためだけの薄いヘルパ）。 */
internal fun replayStream(events: List<ServerSentEvent>) = ReplayHttpTransport.ReplayEventStream(events)
