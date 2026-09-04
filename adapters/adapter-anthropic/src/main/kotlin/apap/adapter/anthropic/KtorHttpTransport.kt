package apap.adapter.anthropic

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * [HttpTransport]の実API向け実装。
 *
 * ## Provider側Retryを使わない（15.1 Step1）
 *
 * Ktorの`HttpRequestRetry`プラグインは**意図的に入れていない**。Retryは
 * 02_システム仕様.md 2.11のRetry EngineがAPAP側で一元制御する。ここで再送すると
 * 試行回数が二重になり、Circuit Breakerの失敗率計算とRetry予算の両方が狂う。
 *
 * ## タイムアウト
 *
 * `AdapterRequest.timeout`（＝残予算）を呼出ごとに設定する。クライアント全体の固定値に
 * しないのは、残予算が呼出ごとに変わるため。
 */
class KtorHttpTransport(
    private val baseUrl: String,
    client: HttpClient? = null,
) : HttpTransport {
    private val client: HttpClient =
        client ?: HttpClient(CIO) {
            // 4xx/5xxを例外にせず、ErrorMapperが本文まで見て8分類へ写せるようにする。
            expectSuccess = false
            install(HttpTimeout)
        }

    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun send(request: HttpCall): HttpReply =
        this.client.prepareRequest { applyTo(this, request) }.execute { response ->
            HttpReply(
                status = response.status.value,
                headers = response.headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") },
                body = response.bodyAsText(),
            )
        }

    override suspend fun openEventStream(request: HttpCall): EventStream {
        // RENDEZVOUS: 下流が受け取るまで送信側が進まない。SSE読み取りが止まればTCPの
        // 読み取りも止まるため、SPIのpull型next()が意図するバックプレッシャがそのまま効く。
        val events = Channel<ServerSentEvent>(Channel.RENDEZVOUS)
        val failure = CompletableDeferred<Throwable?>()
        val job =
            streamScope.launch {
                val outcome =
                    runCatching {
                        this@KtorHttpTransport
                            .client
                            .prepareRequest { applyTo(this, request) }
                            .execute { response -> pumpEvents(response.bodyAsChannel(), events) }
                    }
                events.close()
                failure.complete(outcome.exceptionOrNull())
            }
        return ChannelEventStream(events, job, failure)
    }

    override fun close() {
        streamScope.cancel()
        client.close()
    }

    private fun applyTo(
        builder: HttpRequestBuilder,
        request: HttpCall,
    ) {
        builder.method = HttpMethod.parse(request.method)
        builder.url("$baseUrl${request.path}")
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        request.body?.let {
            builder.contentType(ContentType.Application.Json)
            builder.setBody(it)
        }
        builder.timeout {
            requestTimeoutMillis = request.timeout.toMillis()
            socketTimeoutMillis = request.timeout.toMillis()
        }
    }

    private suspend fun pumpEvents(
        channel: ByteReadChannel,
        events: Channel<ServerSentEvent>,
    ) {
        var eventName: String? = null
        val data = StringBuilder()
        while (true) {
            val line = channel.readUTF8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        events.send(ServerSentEvent(eventName, data.toString()))
                        data.clear()
                        eventName = null
                    }
                }
                line.startsWith(EVENT_PREFIX) -> eventName = line.removePrefix(EVENT_PREFIX).trim()
                line.startsWith(DATA_PREFIX) -> data.append(line.removePrefix(DATA_PREFIX).trim())
                // `:` 始まりはSSEのコメント（keep-alive）。読み飛ばす。
                else -> Unit
            }
        }
    }

    private class ChannelEventStream(
        private val events: Channel<ServerSentEvent>,
        private val job: Job,
        private val failure: CompletableDeferred<Throwable?>,
    ) : EventStream {
        override suspend fun next(): ServerSentEvent? {
            events.receiveCatching().getOrNull()?.let { return it }
            // 終端。ネットワーク例外で終わった場合は握り潰さず投げ直す——握り潰すと
            // 「静かに短いストリーム」になり、切断と正常終了が区別できなくなる。
            failure.await()?.let { throw it }
            return null
        }

        override fun cancel() {
            job.cancel()
            events.cancel()
        }
    }

    private companion object {
        const val EVENT_PREFIX = "event:"
        const val DATA_PREFIX = "data:"
    }
}
