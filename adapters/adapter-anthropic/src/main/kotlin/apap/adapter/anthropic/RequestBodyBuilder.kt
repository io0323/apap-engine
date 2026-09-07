package apap.adapter.anthropic

import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.AudioContentPart
import apap.adapter.spi.ContentPart
import apap.adapter.spi.ImageContentPart
import apap.adapter.spi.InputMessage
import apap.adapter.spi.JsonContentPart
import apap.adapter.spi.TextContentPart
import apap.adapter.spi.ToolDefinition
import apap.adapter.spi.TurnRole
import apap.adapter.spi.VideoContentPart
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * [AdapterRequest] を Provider の Messages API リクエストボディへ写す。
 *
 * ここがSPIと実APIの形が最も食い違う場所で、3つの正規化が要る。詳細と、それが
 * 「SPIの不足」なのか「このProviderが特殊なだけ」なのかの判断は
 * docs/adapter-spi-findings.md に記録している。
 *
 * 1. **system の巻き上げ**: 実APIは system を messages の中のroleではなく
 *    トップレベルのパラメタとして受け取る。SPIの `messages` から SYSTEM を抜き出して
 *    `system` へ移す。SPI側は role を持っているので情報は足りており、写像で吸収できる。
 * 2. **role の交互化**: 実APIは user/assistant が交互に並び、user で始まることを要求する。
 *    SPIは並びを保証しないため、同role連続はマージし、先頭が assistant なら
 *    空の user を補う。
 * 3. **tool_result の位置**: 実APIは tool_result を **user メッセージの中の
 *    content block** として受け取るが、SPIは `toolResults` を messages と別の
 *    トップレベル配列で渡す。callId で対応付けて user メッセージへ合成する。
 *
 * `TooManyFunctions`を抑制しているのは、上記の正規化をそれぞれ独立した関数として
 * 置いているため。1つに畳むと、どの正規化がどの制約に対応するのかが読めなくなる。
 */
@Suppress("TooManyFunctions")
object RequestBodyBuilder {
    private val mapper = ObjectMapper()

    /**
     * @param maxTokensFallback `GenerationParams.maxTokens` が未指定のときに使う値。
     *   実APIは `max_tokens` が**必須**だがSPIでは任意のため、Adapterが埋めるほかない
     *   （findings: SPI変更が必要な項目）。
     */
    fun build(
        request: AdapterRequest,
        stream: Boolean,
        maxTokensFallback: Int,
        structuredOutputMode: StructuredOutputMode,
    ): String {
        val body = mapper.createObjectNode()
        body.put("model", request.modelName)
        // ADR-0040: リクエスト指定 → Modelの上限 → Adapterの既定 の順で解決する。
        // 以前はModelの上限がAdapterへ渡らず、8192で登録したModelでも既定4096で頭打ちになっていた。
        body.put("max_tokens", request.params.maxTokens ?: request.modelMaxOutputTokens ?: maxTokensFallback)
        body.put("stream", stream)

        systemTextOf(request, structuredOutputMode)?.let { body.put("system", it) }
        body.set<ArrayNode>("messages", messagesOf(request))

        request.params.temperature?.let { body.put("temperature", it) }
        request.params.topP?.let { body.put("top_p", it) }
        if (request.params.stop.isNotEmpty()) {
            val stops = mapper.createArrayNode()
            request.params.stop.forEach { stops.add(it) }
            body.set<ArrayNode>("stop_sequences", stops)
        }
        // ADR-0040: seedは実APIに対応パラメタが無い。**黙って捨てない**——
        // 呼び出し元は再現性を期待しており、無言で無視すると効いていないことに気付けない。
        // 拒否はAdapter側で行う（[AnthropicAdapter.buildBody]がUNSUPPORTED_CAPABILITYへ変換する）。
        if (request.params.seed != null) throw AdapterUnsupportedParamException("params.seed")

        request.tools?.takeIf { it.isNotEmpty() }?.let { body.set<ArrayNode>("tools", toolsArray(it)) }
        applyStructuredOutput(body, request.outputSchema, structuredOutputMode)
        return mapper.writeValueAsString(body)
    }

    /**
     * FR-CAP-003 Structured Output。**以前はここが何もしておらず、`outputSchema`は
     * Adapter内で参照ゼロだった**——スキーマがProviderへ渡らないため、モデルは構造の指示なしに
     * 生成し、毎回まず検証に落ちてからADR-0011の是正リトライで直る動作になっていた。
     * 是正機構は例外的な救済であって常用経路ではない（ADR-0040）。
     *
     * @param mode [StructuredOutputMode.NATIVE] は実APIの `output_config.format` を使う。
     *   [StructuredOutputMode.PROMPT] はスキーマをsystemへ組み込む（[systemTextOf]側で行う）。
     */
    private fun applyStructuredOutput(
        body: ObjectNode,
        schema: String?,
        mode: StructuredOutputMode,
    ) {
        if (schema == null || mode != StructuredOutputMode.NATIVE) return
        val outputConfig = mapper.createObjectNode()
        val format = mapper.createObjectNode()
        format.put("type", "json_schema")
        format.set<ObjectNode>("schema", parseSchema(schema))
        outputConfig.set<ObjectNode>("format", format)
        body.set<ObjectNode>("output_config", outputConfig)
    }

    /** tools を Provider 形式の配列へ。`translateTools` からも使う。 */
    fun toolsArray(tools: List<ToolDefinition>): ArrayNode {
        val array = mapper.createArrayNode()
        tools.forEach { tool ->
            val node = mapper.createObjectNode()
            node.put("name", tool.name)
            node.put("description", tool.description)
            node.set<ObjectNode>("input_schema", parseSchema(tool.parametersSchema))
            array.add(node)
        }
        return array
    }

    /**
     * SYSTEM発話（Memory注入・System Promptの両方がここに来る）を連結する。
     * [StructuredOutputMode.PROMPT]のときはスキーマ指示も末尾へ足す。
     */
    private fun systemTextOf(
        request: AdapterRequest,
        mode: StructuredOutputMode,
    ): String? {
        val declared =
            request.messages
                .filter { it.role == TurnRole.SYSTEM }
                .flatMap { it.content }
                .filterIsInstance<TextContentPart>()
                .joinToString("\n\n") { it.text }
        val schemaInstruction =
            request.outputSchema
                ?.takeIf { mode == StructuredOutputMode.PROMPT }
                ?.let { schema ->
                    "You must reply with JSON that validates against this JSON Schema. " +
                        "Reply with the JSON document only, without prose or code fences.\n$schema"
                }
        val text = listOfNotNull(declared.takeIf { it.isNotBlank() }, schemaInstruction).joinToString("\n\n")
        return text.takeIf { it.isNotBlank() }
    }

    private fun messagesOf(request: AdapterRequest): ArrayNode {
        val conversational = request.messages.filter { it.role != TurnRole.SYSTEM }
        val merged = mergeAdjacent(normalizeLeading(conversational))
        val array = mapper.createArrayNode()
        merged.forEach { message ->
            val node = mapper.createObjectNode()
            node.put("role", wireRoleOf(message.role))
            node.set<ArrayNode>("content", contentArray(message.content))
            array.add(node)
        }
        // tool_result は user メッセージとして最後に足す（直前のassistantのtool_useへの応答）。
        if (request.toolResults.isNotEmpty()) {
            val node = mapper.createObjectNode()
            node.put("role", "user")
            val content = mapper.createArrayNode()
            request.toolResults.forEach { result ->
                val block = mapper.createObjectNode()
                block.put("type", "tool_result")
                block.put("tool_use_id", result.callId)
                block.put("content", result.content)
                if (result.isError) block.put("is_error", true)
                content.add(block)
            }
            node.set<ArrayNode>("content", content)
            array.add(node)
        }
        return array
    }

    /**
     * 実APIは先頭が user であることを要求する。履歴の切り詰め（ContextManagerの圧縮）で
     * 先頭が assistant になることは実際に起こりうるため、空の user を補って形を整える。
     */
    private fun normalizeLeading(messages: List<InputMessage>): List<InputMessage> =
        if (messages.isEmpty() || messages.first().role == TurnRole.USER) {
            messages
        } else {
            listOf(InputMessage(TurnRole.USER, listOf(TextContentPart(CONTINUATION_PLACEHOLDER)))) + messages
        }

    /** 同roleの連続をマージする（実APIは交互を要求する）。 */
    private fun mergeAdjacent(messages: List<InputMessage>): List<InputMessage> =
        messages.fold(mutableListOf()) { acc, message ->
            val last = acc.lastOrNull()
            if (last != null && wireRoleOf(last.role) == wireRoleOf(message.role)) {
                acc[acc.lastIndex] = last.copy(content = last.content + message.content)
            } else {
                acc.add(message)
            }
            acc
        }

    /** TOOL roleは実APIに対応する role が無く、tool_result として user 側で表現する。 */
    private fun wireRoleOf(role: TurnRole): String =
        when (role) {
            TurnRole.ASSISTANT -> "assistant"
            TurnRole.USER, TurnRole.TOOL, TurnRole.SYSTEM -> "user"
        }

    private fun contentArray(parts: List<ContentPart>): ArrayNode {
        val array = mapper.createArrayNode()
        parts.forEach { part -> array.add(contentBlock(part)) }
        return array
    }

    /**
     * ContentPartをcontent blockへ。
     *
     * 画像は実APIが `source` オブジェクト（base64 か url）を要求する。SPIの
     * [ImageContentPart] は `uri` + `mimeType` なので、httpスキームは url 形式、
     * `data:` スキームは base64 形式へ振り分ける。音声・動画は Messages API に
     * 対応する block が無く、ここでは表現できない（findings参照）。
     */
    private fun contentBlock(part: ContentPart): ObjectNode {
        val node = mapper.createObjectNode()
        when (part) {
            is TextContentPart -> {
                node.put("type", "text")
                node.put("text", part.text)
            }
            is ImageContentPart -> {
                node.put("type", "image")
                node.set<ObjectNode>("source", imageSource(part))
            }
            is JsonContentPart -> {
                // JSONはテキストとして渡す（実APIにJSON専用blockが無い）。
                node.put("type", "text")
                node.put("text", part.json)
            }
            is AudioContentPart -> throw AdapterModalityException("Audio")
            is VideoContentPart -> throw AdapterModalityException("Video")
        }
        return node
    }

    private fun imageSource(part: ImageContentPart): ObjectNode {
        val source = mapper.createObjectNode()
        val dataPrefix = "data:"
        if (part.uri.startsWith(dataPrefix)) {
            source.put("type", "base64")
            source.put("media_type", part.mimeType)
            source.put("data", part.uri.substringAfter(",", ""))
        } else {
            source.put("type", "url")
            source.put("url", part.uri)
        }
        return source
    }

    private fun parseSchema(schema: String): ObjectNode =
        runCatching { mapper.readTree(schema) as ObjectNode }
            .getOrElse {
                // スキーマが壊れているまま送ると、Provider側で分かりにくい400になる。
                // 手前で落として INVALID_REQUEST として扱えるようにする。
                throw AdapterSchemaException(it.message ?: "invalid tool input schema")
            }

    private const val CONTINUATION_PLACEHOLDER = "(continued)"
}

/** Messages APIに対応するcontent blockが無いmodalityを渡された。 */
class AdapterModalityException(
    val modality: String,
) : RuntimeException("this provider's messages API has no content block for modality: $modality")

/** Providerに対応する概念が無いパラメタを指定された。黙って捨てないための明示的な失敗。 */
class AdapterUnsupportedParamException(
    val paramName: String,
) : RuntimeException("this provider has no equivalent for $paramName; it would be silently ignored")

/** tool の input_schema がJSONとして壊れている。 */
class AdapterSchemaException(
    detail: String,
) : RuntimeException("tool input schema is not valid JSON: $detail")
