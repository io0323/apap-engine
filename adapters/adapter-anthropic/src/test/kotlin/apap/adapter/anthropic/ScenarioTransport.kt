package apap.adapter.anthropic

import kotlinx.coroutines.delay

/**
 * Contract Testが要求する各シナリオ（正常・8分類のエラー・タイムアウト・Stream）を、
 * **リクエスト本文の見張り文字列**で切り替える[HttpTransport]。
 *
 * リクエストを見て応答を選ぶのは、Contract Testが「同じAdapterインスタンスに対して
 * 異なるリクエストを投げる」形になっているため。応答を先に積む方式だと、どの
 * リクエストにどの応答が対応するのかがテスト側から見えなくなる。
 *
 * エラー応答の本文は実APIのエラー形式（`{"type":"error","error":{"type":...}}`）に合わせてある。
 * 出所は公開仕様であって実記録ではない（[ReplayHttpTransport]のKDoc参照）。
 */
class ScenarioTransport : HttpTransport {
    val calls = mutableListOf<HttpCall>()
    var lastStream: ReplayHttpTransport.ReplayEventStream? = null
        private set

    override suspend fun send(request: HttpCall): HttpReply {
        calls += request
        val body = request.body.orEmpty()
        val recorded =
            if (request.method == "GET" && request.path == AnthropicAdapter.MODELS_PATH) {
                ReplayHttpTransport.load("models-list").reply!!
            } else {
                null
            }
        return recorded ?: errorFor(body) ?: successReply(body)
    }

    private suspend fun successReply(body: String): HttpReply {
        if (body.contains(SLOW_MARKER)) {
            // タイムアウト検証用。AdapterRequest.timeoutより十分長く待つ。
            delay(SLOW_DELAY_MILLIS)
        }
        return ReplayHttpTransport.load("chat-basic").reply!!
    }

    override suspend fun openEventStream(request: HttpCall): EventStream {
        calls += request
        val recording =
            if (request.body.orEmpty().contains(STREAM_TOOL_MARKER)) "stream-tool-use" else "stream-text"
        return ReplayHttpTransport
            .ReplayEventStream(ReplayHttpTransport.load(recording).events)
            .also { lastStream = it }
    }

    override fun close() = Unit

    private fun errorFor(body: String): HttpReply? =
        when {
            body.contains("force-error:transient") -> error(STATUS_SERVER_ERROR, "api_error", "Internal server error")
            body.contains("force-error:rate-limited") ->
                error(STATUS_RATE_LIMITED, "rate_limit_error", "Rate limited", mapOf("retry-after" to "7"))
            body.contains("force-error:invalid-request") ->
                error(STATUS_BAD_REQUEST, "invalid_request_error", "messages: field required")
            body.contains("force-error:auth") ->
                error(STATUS_UNAUTHORIZED, "authentication_error", "invalid x-api-key")
            body.contains("force-error:model") ->
                error(STATUS_NOT_FOUND, "not_found_error", "model: unknown model")
            body.contains("force-error:overloaded") ->
                error(STATUS_OVERLOADED, "overloaded_error", "Overloaded")
            else -> null
        }

    private fun error(
        status: Int,
        type: String,
        message: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpReply =
        HttpReply(
            status = status,
            headers = headers,
            body = """{"type":"error","error":{"type":"$type","message":"$message"}}""",
        )

    companion object {
        /** タイムアウト検証で使う。この文字列を含むリクエストだけ応答を遅らせる。 */
        const val SLOW_MARKER = "force-slow:timeout"
        const val STREAM_TOOL_MARKER = "force-stream:tool"

        private const val SLOW_DELAY_MILLIS = 5_000L
        private const val STATUS_BAD_REQUEST = 400
        private const val STATUS_UNAUTHORIZED = 401
        private const val STATUS_NOT_FOUND = 404
        private const val STATUS_RATE_LIMITED = 429
        private const val STATUS_SERVER_ERROR = 500
        private const val STATUS_OVERLOADED = 529
    }
}
