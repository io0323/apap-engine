package apap.adapter.anthropic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 実APIとのやりとりを記録しながら中継する[HttpTransport]。[LiveProviderTest]からのみ使う。
 *
 * ## マスキング
 *
 * リクエストヘッダは**記録しない**（`x-api-key`が含まれるため）。応答本文は記録するが、
 * 識別子らしきフィールドは伏字にする。それでも完全ではないので、
 * 生成物は必ず目視で確認してから `src/test/resources/recordings/` へ置くこと。
 */
class RecordingHttpTransport(
    private val delegate: HttpTransport,
    private val outputDir: File,
) : HttpTransport {
    private val mapper = ObjectMapper()
    private val sequence = AtomicInteger(0)

    override suspend fun send(request: HttpCall): HttpReply {
        val reply = delegate.send(request)
        val root = mapper.createObjectNode()
        root.put("source", "recorded from live API")
        val replyNode = root.putObject("reply")
        replyNode.put("status", reply.status)
        replyNode.set<ObjectNode>("headers", maskedHeaders(reply.headers))
        replyNode.set<ObjectNode>("body", masked(mapper.readTree(reply.body)))
        write("reply-${sequence.incrementAndGet()}", root)
        return reply
    }

    override suspend fun openEventStream(request: HttpCall): EventStream {
        val source = delegate.openEventStream(request)
        val root = mapper.createObjectNode()
        root.put("source", "recorded from live API")
        val events = root.putArray("events")
        write("stream-${sequence.incrementAndGet()}", root)
        return RecordingEventStream(source, events, root)
    }

    override fun close() = delegate.close()

    private inner class RecordingEventStream(
        private val source: EventStream,
        private val events: ArrayNode,
        private val root: ObjectNode,
    ) : EventStream {
        private val name = "stream-${sequence.get()}"

        override suspend fun next(): ServerSentEvent? {
            val event = source.next()
            if (event == null) {
                write(name, root)
                return null
            }
            val node = events.addObject()
            node.put("event", event.event)
            node.set<ObjectNode>("data", masked(mapper.readTree(event.data)))
            return event
        }

        override fun cancel() {
            write(name, root)
            source.cancel()
        }
    }

    /** 応答ヘッダのうち、識別子を伏字にする。リクエストヘッダは一切記録しない。 */
    private fun maskedHeaders(headers: Map<String, String>): ObjectNode {
        val node = mapper.createObjectNode()
        headers.forEach { (key, value) ->
            val masked = if (key in MASKED_HEADERS) "redacted" else value
            node.put(key, masked)
        }
        return node
    }

    /** `id`のような識別子フィールドを再帰的に伏字にする。 */
    private fun masked(node: com.fasterxml.jackson.databind.JsonNode): com.fasterxml.jackson.databind.JsonNode {
        if (node is ObjectNode) {
            node.fieldNames().asSequence().toList().forEach { field ->
                if (field in MASKED_FIELDS && node.path(field).isTextual) {
                    node.put(field, "${field}_redacted")
                } else {
                    node.replace(field, masked(node.path(field)))
                }
            }
        } else if (node is ArrayNode) {
            for (i in 0 until node.size()) node.set(i, masked(node[i]))
        }
        return node
    }

    private fun write(
        name: String,
        node: ObjectNode,
    ) {
        File(outputDir, "$name.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node))
    }

    private companion object {
        val MASKED_HEADERS = setOf("request-id", "x-request-id", "cf-ray", "anthropic-organization-id")
        val MASKED_FIELDS = setOf("id", "request_id", "organization_id", "account_id")
    }
}
