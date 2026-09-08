package apap.provider

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * enumの綴り差（大文字小文字）だけを許容する仕組み（[EnumCaseNormalizer]と
 * [JsonSchemaValidator]の`enumCaseInsensitive`）。
 *
 * 緩めすぎれば検証の意味が消え、厳しすぎれば直しようのない差分で是正リトライが空回りする。
 * 「綴りだけ」を境界として固定する。
 */
class EnumCaseNormalizerTest {
    private val schema =
        """
        {"type":"object","required":["unit"],
         "properties":{"unit":{"type":"string","enum":["Celsius","Fahrenheit"]}}}
        """.trimIndent()

    @Test
    fun `a case-only difference is treated as conforming when tolerance is on`() {
        val json = """{"unit":"celsius"}"""
        assertFalse(JsonSchemaValidator.validate(schema, json).valid, "既定では厳密であるべきです")
        assertTrue(JsonSchemaValidator.validate(schema, json, enumCaseInsensitive = true).valid)
    }

    @Test
    fun `a value that is not in the enum stays invalid`() {
        val result = JsonSchemaValidator.validate(schema, """{"unit":"kelvin"}""", enumCaseInsensitive = true)
        assertFalse(result.valid, "enumに無い値まで通しています")
    }

    @Test
    fun `other violations are unaffected by the tolerance`() {
        // requiredの欠落は綴りと無関係。緩めた側で通してはならない。
        val result = JsonSchemaValidator.validate(schema, """{}""", enumCaseInsensitive = true)
        assertFalse(result.valid)
    }

    @Test
    fun `enums nested in arrays, objects and defs are reached`() {
        val nested =
            """
            {"type":"object",
             "properties":{
               "readings":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/reading"}}
             },
             "${'$'}defs":{
               "reading":{"type":"object",
                 "properties":{"unit":{"type":"string","enum":["Celsius"]}}}
             }}
            """.trimIndent()
        val json = """{"readings":[{"unit":"CELSIUS"}]}"""

        assertFalse(JsonSchemaValidator.validate(nested, json).valid)
        assertTrue(
            JsonSchemaValidator.validate(nested, json, enumCaseInsensitive = true).valid,
            "入れ子（配列→\$ref→object）のenumに届いていません",
        )
    }

    @Test
    fun `nothing to normalize returns null so the caller keeps the original text`() {
        assertNull(EnumCaseNormalizer.normalize(schema, """{"unit":"Celsius"}"""))
        assertNull(EnumCaseNormalizer.normalize(schema, "not json"), "壊れたJSONは呼び出し側の判定に委ねる")
    }

    /** 判定を緩めるだけで、値そのものは書き換えない（呼び出し側の応答は原文のまま）。 */
    @Test
    fun `normalization produces a copy and leaves the input untouched`() {
        val json = """{"unit":"celsius"}"""
        val normalized = EnumCaseNormalizer.normalize(schema, json)
        assertTrue(normalized.orEmpty().contains("Celsius"), "寄せた写しが返っていません: $normalized")
        assertTrue(json.contains("celsius"), "入力が書き換えられています")
    }
}
