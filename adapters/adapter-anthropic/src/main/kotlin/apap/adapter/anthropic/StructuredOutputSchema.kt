package apap.adapter.anthropic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * `output_config.format.schema` へ載せる前に、JSON Schemaを実APIが受け付ける部分集合へ整える。
 *
 * ## なぜAdapterがやるのか
 *
 * 実APIの構造化出力は、スキーマを文法へコンパイルして生成を拘束する方式で、
 * **受け付けるJSON Schemaの部分集合が決まっている**。各言語の公式SDKはこの整形を
 * 内部で行っているが、本Adapterは生HTTPを直接叩くため自分で行う必要がある。
 * 整形しないまま送ると400になり、リクエストは通らない。
 *
 * ## 行うこと（出典: [DOC_SOURCE]）
 *
 * 1. **すべてのobjectへ`additionalProperties: false`を付ける**（必須。`false`以外の値は不可）
 * 2. **サポート外の制約キーワードを外す**——数値域（`minimum`/`maximum`/`exclusiveMinimum`/
 *    `exclusiveMaximum`/`multipleOf`）、文字列長（`minLength`/`maxLength`）、
 *    配列の個数（`maxItems`と2以上の`minItems`、`uniqueItems`）、対応外の`format`。
 * 3. 外した制約は**`description`へ書き足す**（"Must be at least 100." 等）。
 *    公式SDKと同じ扱いで、拘束はできなくてもモデルへの指示としては残す。
 *
 * ## 外した制約はどこで効くのか
 *
 * **応答の検証は元のスキーマに対して行う**（`AttemptExecutor`がリクエストの`outputSchema`を
 * そのまま使う）。つまり、ここで外した`minimum`等は「送信時に拘束されない」だけで
 * 「検証されない」わけではない。整形後のスキーマで検証してしまうと、利用側が課した制約が
 * 黙って消えることになる——それを避けるため、整形結果はリクエスト本文にしか使わない。
 */
object StructuredOutputSchema {
    /** 記録用の出典。実装判断の根拠をコード側にも残す（findings §9.8と対）。 */
    const val DOC_SOURCE = "https://platform.claude.com/docs/en/build-with-claude/structured-outputs"

    private val mapper = ObjectMapper()

    /** 実APIが受け付ける`format`（文字列）。これ以外は外して`description`へ回す。 */
    private val SUPPORTED_FORMATS =
        setOf("date-time", "time", "date", "duration", "email", "hostname", "uri", "ipv4", "ipv6", "uuid")

    /** 外した制約を`description`へ書き足すときの文面。 */
    private val CONSTRAINT_NOTES: Map<String, (JsonNode) -> String> =
        mapOf(
            "minimum" to { v -> "Must be at least $v." },
            "maximum" to { v -> "Must be at most $v." },
            "exclusiveMinimum" to { v -> "Must be greater than $v." },
            "exclusiveMaximum" to { v -> "Must be less than $v." },
            "multipleOf" to { v -> "Must be a multiple of $v." },
            "minLength" to { v -> "Must be at least $v characters long." },
            "maxLength" to { v -> "Must be at most $v characters long." },
            "maxItems" to { v -> "Must have at most $v items." },
            "uniqueItems" to { _ -> "Items must be unique." },
        )

    private val CHILD_SCHEMA_KEYWORDS = listOf("items", "additionalItems", "contains", "not", "if", "then", "else")
    private val CHILD_SCHEMA_ARRAYS = listOf("anyOf", "allOf", "oneOf", "prefixItems")
    private val CHILD_SCHEMA_MAPS = listOf("properties", "\$defs", "definitions", "patternProperties")

    /**
     * 整形した写しを返す。引数は書き換えない。
     *
     * @throws AdapterSchemaException スキーマがJSONオブジェクトとして読めない場合。
     *   壊れたまま送ると分かりにくい400になるので、手前で落としてINVALID_REQUESTへ写す。
     */
    fun prepare(schema: String): ObjectNode {
        val parsed =
            runCatching { mapper.readTree(schema) }
                .getOrNull()
                ?.takeIf { it.isObject }
                ?: throw AdapterSchemaException("output schema is not a JSON object")
        return transform(parsed.deepCopy<ObjectNode>(), 0)
    }

    private fun transform(
        node: ObjectNode,
        depth: Int,
    ): ObjectNode {
        if (depth > MAX_DEPTH) return node
        stripUnsupported(node)
        normalizeMinItems(node)
        normalizeFormat(node)
        if (isObjectSchema(node)) node.put("additionalProperties", false)
        descendInto(node, depth)
        return node
    }

    private fun descendInto(
        node: ObjectNode,
        depth: Int,
    ) {
        CHILD_SCHEMA_MAPS.forEach { keyword ->
            val container = node.path(keyword) as? ObjectNode ?: return@forEach
            container.fieldNames().asSequence().toList().forEach { field ->
                (container.path(field) as? ObjectNode)?.let { transform(it, depth + 1) }
            }
        }
        CHILD_SCHEMA_ARRAYS.forEach { keyword ->
            val array = node.path(keyword) as? ArrayNode ?: return@forEach
            array.forEach { element -> (element as? ObjectNode)?.let { transform(it, depth + 1) } }
        }
        CHILD_SCHEMA_KEYWORDS.forEach { keyword ->
            (node.path(keyword) as? ObjectNode)?.let { transform(it, depth + 1) }
        }
    }

    /** サポート外の制約を外し、外した内容を`description`へ移す。 */
    private fun stripUnsupported(node: ObjectNode) {
        val notes =
            CONSTRAINT_NOTES.mapNotNull { (keyword, describe) ->
                val value = node.path(keyword).takeIf { !it.isMissingNode } ?: return@mapNotNull null
                node.remove(keyword)
                describe(value)
            }
        appendToDescription(node, notes)
    }

    /** `minItems`は0か1のみ受け付ける。2以上は外して文面へ回す。 */
    private fun normalizeMinItems(node: ObjectNode) {
        val minItems = node.path("minItems").takeIf { it.isNumber } ?: return
        if (minItems.asInt() <= 1) return
        node.remove("minItems")
        appendToDescription(node, listOf("Must have at least ${minItems.asInt()} items."))
    }

    /** 対応外の`format`は外す（型そのものは残るので、指示としてだけ残す）。 */
    private fun normalizeFormat(node: ObjectNode) {
        val format = node.path("format").takeIf { it.isTextual }?.asText() ?: return
        if (format in SUPPORTED_FORMATS) return
        node.remove("format")
        appendToDescription(node, listOf("Format: $format."))
    }

    private fun appendToDescription(
        node: ObjectNode,
        notes: List<String>,
    ) {
        if (notes.isEmpty()) return
        val existing =
            node
                .path("description")
                .takeIf { it.isTextual }
                ?.asText()
                .orEmpty()
        val merged = (listOfNotNull(existing.takeIf { it.isNotBlank() }) + notes).joinToString(" ")
        node.put("description", merged)
    }

    /**
     * objectスキーマかどうか。`type`宣言が無くても`properties`があればobjectとして扱う
     * （JSON Schemaでは`type`は任意で、`properties`だけを書く流儀が実際に多い）。
     */
    private fun isObjectSchema(node: ObjectNode): Boolean =
        node.path("type").asText("") == "object" ||
            node.path("type").let { it.isArray && it.any { entry -> entry.asText() == "object" } } ||
            node.has("properties")

    /** 循環しうる`$ref`を持つスキーマでも停止するための打ち切り深さ。 */
    private const val MAX_DEPTH = 32
}
