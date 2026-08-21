package apap.provider.json

/**
 * `CapabilityRegistry`のJSON Schema入出力検証に使う、最小限の自前JSON Schemaバリデータ
 * （[Json.kt][JsonParser]と同じ理由でフル機能実装を依存に加えない）。
 * サポートするキーワード: `type`（文字列または配列）/ `required` / `properties` / `items` / `enum`。
 * 未対応のキーワード（`pattern`, `minimum`, `additionalProperties`等）は無視する
 * （将来のRequest/Response Mapper実装時に段階的に拡充する前提。CLAUDE.md「ADR化するか否かの判断基準」:
 * 命名・実装詳細でありFR-CAP-017の充足自体には影響しないためADR化しない）。
 */
object JsonSchemaValidator {
    /** 違反メッセージのリストを返す。空リスト = 適合。 */
    fun validate(
        schema: JsonValue,
        instance: JsonValue,
        path: String = "$",
    ): List<String> {
        if (schema !is JsonValue.JsonObject) return emptyList()
        return validateType(schema, instance, path) +
            validateEnum(schema, instance, path) +
            validateObject(schema, instance, path) +
            validateArray(schema, instance, path)
    }

    private fun validateType(
        schema: JsonValue.JsonObject,
        instance: JsonValue,
        path: String,
    ): List<String> {
        val typeDeclaration = schema.members["type"] ?: return emptyList()
        val allowedTypes = allowedTypeNames(typeDeclaration)
        val matches = allowedTypes.isEmpty() || allowedTypes.any { matchesType(instance, it) }
        val message = "$path: expected type in $allowedTypes but was ${typeNameOf(instance)}"
        return if (matches) emptyList() else listOf(message)
    }

    private fun validateEnum(
        schema: JsonValue.JsonObject,
        instance: JsonValue,
        path: String,
    ): List<String> {
        val allowed = schema.members["enum"] as? JsonValue.JsonArray ?: return emptyList()
        val message = "$path: value is not one of the allowed enum values"
        return if (allowed.items.any { it == instance }) emptyList() else listOf(message)
    }

    private fun validateObject(
        schema: JsonValue.JsonObject,
        instance: JsonValue,
        path: String,
    ): List<String> {
        if (instance !is JsonValue.JsonObject) return emptyList()
        val requiredErrors =
            (schema.members["required"] as? JsonValue.JsonArray)?.items.orEmpty().mapNotNull { requiredName ->
                val name = (requiredName as? JsonValue.JsonString)?.value
                if (name != null && name !in instance.members) "$path: missing required property \"$name\"" else null
            }
        val properties = (schema.members["properties"] as? JsonValue.JsonObject)?.members.orEmpty()
        val propertyErrors =
            properties.flatMap { (name, propertySchema) ->
                instance.members[name]?.let { validate(propertySchema, it, "$path.$name") }.orEmpty()
            }
        return requiredErrors + propertyErrors
    }

    private fun validateArray(
        schema: JsonValue.JsonObject,
        instance: JsonValue,
        path: String,
    ): List<String> {
        val itemSchema = schema.members["items"]
        return if (instance !is JsonValue.JsonArray || itemSchema == null) {
            emptyList()
        } else {
            instance.items.flatMapIndexed { index, item -> validate(itemSchema, item, "$path[$index]") }
        }
    }

    private fun allowedTypeNames(declaration: JsonValue): Set<String> =
        when (declaration) {
            is JsonValue.JsonString -> setOf(declaration.value)
            is JsonValue.JsonArray ->
                declaration.items
                    .filterIsInstance<JsonValue.JsonString>()
                    .map { it.value }
                    .toSet()
            else -> emptySet()
        }

    /** JSON Schemaの`"number"`は整数値も受理する。`"integer"`は整数値のみを受理する。 */
    private fun matchesType(
        value: JsonValue,
        typeName: String,
    ): Boolean =
        when (typeName) {
            "integer" -> value is JsonValue.JsonNumber && value.value == Math.floor(value.value)
            "number" -> value is JsonValue.JsonNumber
            else -> typeNameOf(value) == typeName
        }

    private fun typeNameOf(value: JsonValue): String =
        when (value) {
            is JsonValue.JsonObject -> "object"
            is JsonValue.JsonArray -> "array"
            is JsonValue.JsonString -> "string"
            is JsonValue.JsonNumber -> "number"
            is JsonValue.JsonBoolean -> "boolean"
            JsonValue.JsonNull -> "null"
        }
}
