package apap.provider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * スキーマが宣言する`enum`の**綴り（大文字小文字）**へ、インスタンス側の文字列を寄せる。
 *
 * ## なぜ要るのか
 *
 * 制約付きデコード（スキーマから文法を組んで生成を拘束する方式）を採るProviderでは、
 * **enum値の大文字小文字までは保証されない**ことがある。`"Celsius"`と宣言した列挙に対して
 * `"celsius"`が返り、しかも応答は正常終了する（それを示す終了理由も出ない）。
 *
 * 厳密なJSON Schema検証はこれを不適合と判定するため、**意味的には正しい応答に対して
 * 是正リトライ（ADR-0011）が走る**。是正予算を、直しようのない差分で使い切ることになる。
 *
 * そこで検証の**前に**、スキーマ側の綴りへ寄せた写しを作り、その写しを検証する。
 * 応答そのものは書き換えない——利用側へ返るのはProviderが返した値のままで、
 * ここで変わるのは「適合と見なすかどうか」の判定だけである。
 *
 * ## 対応範囲
 *
 * `properties` / `items` / `prefixItems` / `additionalProperties` / `allOf` / `anyOf` / `oneOf` と、
 * 同一ドキュメント内の`$ref`（`#/$defs/...`等のJSON Pointer）をたどる。
 * 外部参照は解決できないため、その先は**寄せずにそのまま**——つまり従来どおり厳密に判定する。
 * 循環参照と過度な入れ子は[MAX_DEPTH]で打ち切る（悪意あるスキーマで停止しないため）。
 *
 * `ReturnCount`を抑制しているのは、この走査が「この枝は対象外なので**そのまま返す**」という
 * ガード節の連なりで書かれているため。入れ子のifへ畳むと、どの条件で寄せないのかが読めなくなる。
 */
@Suppress("ReturnCount")
object EnumCaseNormalizer {
    private val mapper = ObjectMapper()

    /**
     * [instanceJson]を[schemaJson]のenum綴りへ寄せたJSON文字列。
     * 寄せる箇所が無ければ、また、どちらかがJSONとして読めなければnull（呼び出し側は原文を使う）。
     */
    fun normalize(
        schemaJson: String,
        instanceJson: String,
    ): String? {
        val schema = readOrNull(schemaJson) ?: return null
        val instance = readOrNull(instanceJson) ?: return null
        val normalized = walk(schema, instance.deepCopy<JsonNode>(), schema, 0)
        return if (normalized == instance) null else mapper.writeValueAsString(normalized)
    }

    private fun readOrNull(json: String): JsonNode? = runCatching { mapper.readTree(json) }.getOrNull()

    private fun walk(
        schemaNode: JsonNode,
        instance: JsonNode,
        root: JsonNode,
        depth: Int,
    ): JsonNode {
        if (depth > MAX_DEPTH) return instance
        val schema = resolve(schemaNode, root) ?: return instance

        val afterEnum = applyEnum(schema, instance)
        var result = afterEnum
        // 組み合わせキーワードの各枝も見る。寄せる操作は冪等なので重複適用しても害はない。
        for (keyword in COMBINATORS) {
            val branches = schema.path(keyword) as? ArrayNode ?: continue
            branches.forEach { branch -> result = walk(branch, result, root, depth + 1) }
        }
        return when {
            result.isObject -> walkObject(schema, result as ObjectNode, root, depth)
            result.isArray -> walkArray(schema, result as ArrayNode, root, depth)
            else -> result
        }
    }

    private fun applyEnum(
        schema: JsonNode,
        instance: JsonNode,
    ): JsonNode {
        if (!instance.isTextual) return instance
        val values = schema.path("enum").takeIf { it.isArray } ?: return instance
        val candidates = values.filter { it.isTextual }.map { it.asText() }
        if (candidates.any { it == instance.asText() }) return instance
        val match = candidates.firstOrNull { it.equals(instance.asText(), ignoreCase = true) } ?: return instance
        return mapper.nodeFactory.textNode(match)
    }

    private fun walkObject(
        schema: JsonNode,
        instance: ObjectNode,
        root: JsonNode,
        depth: Int,
    ): ObjectNode {
        val properties = schema.path("properties")
        val additional = schema.path("additionalProperties")
        instance.fieldNames().asSequence().toList().forEach { field ->
            val propertySchema =
                properties.path(field).takeIf { !it.isMissingNode }
                    ?: additional.takeIf { it.isObject }
                    ?: return@forEach
            instance.set<JsonNode>(field, walk(propertySchema, instance.path(field), root, depth + 1))
        }
        return instance
    }

    private fun walkArray(
        schema: JsonNode,
        instance: ArrayNode,
        root: JsonNode,
        depth: Int,
    ): ArrayNode {
        val items = schema.path("items").takeIf { it.isObject }
        val prefixItems = schema.path("prefixItems") as? ArrayNode
        for (index in 0 until instance.size()) {
            val elementSchema =
                prefixItems?.takeIf { index < it.size() }?.get(index) ?: items ?: continue
            instance.set(index, walk(elementSchema, instance.get(index), root, depth + 1))
        }
        return instance
    }

    /** 同一ドキュメント内の`$ref`だけを解決する。外部参照・未解決はnull（その枝は寄せない）。 */
    private fun resolve(
        schema: JsonNode,
        root: JsonNode,
    ): JsonNode? {
        if (!schema.isObject) return null
        val ref = schema.path("\$ref").asText("")
        if (ref.isEmpty()) return schema
        if (!ref.startsWith("#")) return null
        val pointer = ref.removePrefix("#")
        val target = if (pointer.isEmpty()) root else root.at(pointer)
        return target.takeIf { !it.isMissingNode && it.isObject }
    }

    private val COMBINATORS = listOf("allOf", "anyOf", "oneOf")

    /** 循環`$ref`と過度な入れ子で停止しないための打ち切り深さ。 */
    private const val MAX_DEPTH = 32
}
