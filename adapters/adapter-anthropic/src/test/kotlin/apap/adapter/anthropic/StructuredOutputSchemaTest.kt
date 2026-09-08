package apap.adapter.anthropic

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `output_config.format.schema` へ載せる前のスキーマ前処理（[StructuredOutputSchema]）。
 *
 * 各言語の公式SDKが内部で行っている整形を、生HTTPを叩く本Adapterは自分で行う必要がある。
 * 整形漏れは400として現れる——つまり**リクエストが1本も通らない**ので、
 * ここが落ちるかどうかは構造化出力が使えるかどうかそのものである。
 * 出典は[StructuredOutputSchema.DOC_SOURCE]。
 */
class StructuredOutputSchemaTest {
    @Test
    fun `every object gets additionalProperties false, including nested ones`() {
        val prepared =
            StructuredOutputSchema.prepare(
                """
                {
                  "type": "object",
                  "properties": {
                    "user": {
                      "type": "object",
                      "properties": { "name": { "type": "string" } }
                    },
                    "items": {
                      "type": "array",
                      "items": { "type": "object", "properties": { "sku": { "type": "string" } } }
                    }
                  }
                }
                """.trimIndent(),
            )

        assertEquals(false, prepared.path("additionalProperties").asBoolean(true))
        assertEquals(false, prepared.at("/properties/user/additionalProperties").asBoolean(true))
        assertEquals(false, prepared.at("/properties/items/items/additionalProperties").asBoolean(true))
        // 配列そのものはobjectではないので付けない。
        assertTrue(prepared.at("/properties/items/additionalProperties").isMissingNode)
    }

    @Test
    fun `objects inside defs and combinators are reached too`() {
        val prepared =
            StructuredOutputSchema.prepare(
                """
                {
                  "type": "object",
                  "properties": { "shape": { "anyOf": [ { "${'$'}ref": "#/${'$'}defs/circle" } ] } },
                  "${'$'}defs": {
                    "circle": { "type": "object", "properties": { "r": { "type": "number" } } }
                  }
                }
                """.trimIndent(),
            )

        assertEquals(
            false,
            prepared.at("/${'$'}defs/circle/additionalProperties").asBoolean(true),
            "\$defs配下のobjectが整形されていません: $prepared",
        )
    }

    @Test
    fun `an additionalProperties value other than false is replaced`() {
        val prepared =
            StructuredOutputSchema.prepare(
                """{"type":"object","additionalProperties":true,"properties":{"a":{"type":"string"}}}""",
            )
        assertEquals(false, prepared.path("additionalProperties").asBoolean(true))
    }

    @Test
    fun `unsupported numeric and string constraints move into the description`() {
        val prepared =
            StructuredOutputSchema.prepare(
                """
                {
                  "type": "object",
                  "properties": {
                    "age": { "type": "integer", "minimum": 0, "maximum": 130 },
                    "code": { "type": "string", "minLength": 3, "maxLength": 3, "description": "Country code." }
                  }
                }
                """.trimIndent(),
            )

        val age = prepared.at("/properties/age")
        assertTrue(age.path("minimum").isMissingNode, "minimumが残っています（400になります）: $age")
        assertTrue(age.path("maximum").isMissingNode)
        assertDescriptionContains(age, "Must be at least 0.")
        assertDescriptionContains(age, "Must be at most 130.")

        val code = prepared.at("/properties/code")
        assertTrue(code.path("minLength").isMissingNode)
        // 元のdescriptionは失わない。制約は書き足す。
        assertDescriptionContains(code, "Country code.")
        assertDescriptionContains(code, "Must be at least 3 characters long.")
    }

    @Test
    fun `minItems survives only when it is zero or one`() {
        val prepared =
            StructuredOutputSchema.prepare(
                """
                {
                  "type": "object",
                  "properties": {
                    "few": { "type": "array", "minItems": 1, "items": { "type": "string" } },
                    "many": { "type": "array", "minItems": 3, "maxItems": 9, "items": { "type": "string" } }
                  }
                }
                """.trimIndent(),
            )

        assertEquals(1, prepared.at("/properties/few/minItems").asInt(-1), "minItems:1は受け付けられる値です")
        val many = prepared.at("/properties/many")
        assertTrue(many.path("minItems").isMissingNode, "2以上のminItemsが残っています: $many")
        assertTrue(many.path("maxItems").isMissingNode)
        assertDescriptionContains(many, "Must have at least 3 items.")
        assertDescriptionContains(many, "Must have at most 9 items.")
    }

    @Test
    fun `only the supported string formats are kept`() {
        val prepared =
            StructuredOutputSchema.prepare(
                """
                {
                  "type": "object",
                  "properties": {
                    "at": { "type": "string", "format": "date-time" },
                    "color": { "type": "string", "format": "hex-color" }
                  }
                }
                """.trimIndent(),
            )

        assertEquals("date-time", prepared.at("/properties/at/format").asText())
        assertTrue(prepared.at("/properties/color/format").isMissingNode)
        assertDescriptionContains(prepared.at("/properties/color"), "Format: hex-color.")
    }

    /** 元のスキーマは書き換えない——応答の検証は元のスキーマに対して行うため。 */
    @Test
    fun `preparing does not mutate the caller's schema text`() {
        val original = """{"type":"object","properties":{"age":{"type":"integer","minimum":18}}}"""
        StructuredOutputSchema.prepare(original)
        assertTrue(original.contains("\"minimum\":18"), "呼び出し側のスキーマが書き換えられています")
    }

    @Test
    fun `a schema that is not a JSON object fails before the request is sent`() {
        assertThrows(AdapterSchemaException::class.java) { StructuredOutputSchema.prepare("[1,2,3]") }
        assertThrows(AdapterSchemaException::class.java) { StructuredOutputSchema.prepare("{not json") }
    }

    private fun assertDescriptionContains(
        node: JsonNode,
        fragment: String,
    ) {
        val description = node.path("description").asText("")
        assertFalse(description.isBlank(), "descriptionが空です: $node")
        assertTrue(
            description.contains(fragment),
            "外した制約がdescriptionへ引き継がれていません。expected to contain <$fragment> but was <$description>",
        )
    }
}
