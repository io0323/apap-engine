package apap.provider.json

/**
 * `CapabilityRegistry`のJSON Schema検証に必要な範囲だけを対象とした、最小限の自前JSONパーサ。
 *
 * `PluginManifestParser`（`apap.adapter.spi.plugin`）が「フル機能のYAML実装を依存に加えず、既知の
 * スキーマだけを読める最小限のパーサとして自前実装する」方針を採った先例（CLAUDE.md「ADR化するか否かの
 * 判断基準」により、依存選定の詳細でありFR/NFRの充足には影響しないためADR化しない）と同じ考え方を、
 * JSONに適用したもの。標準的なJSON文法（object/array/string/number/boolean/null）を一通りサポートする。
 */
sealed interface JsonValue {
    data class JsonObject(
        val members: Map<String, JsonValue>,
    ) : JsonValue

    data class JsonArray(
        val items: List<JsonValue>,
    ) : JsonValue

    data class JsonString(
        val value: String,
    ) : JsonValue

    data class JsonNumber(
        val value: Double,
    ) : JsonValue

    data class JsonBoolean(
        val value: Boolean,
    ) : JsonValue

    object JsonNull : JsonValue
}

class JsonParseException(
    message: String,
) : Exception(message)

object JsonParser {
    fun parse(text: String): JsonValue {
        val cursor = Cursor(text)
        cursor.skipWhitespace()
        val value = cursor.parseValue()
        cursor.skipWhitespace()
        if (!cursor.isAtEnd()) {
            throw JsonParseException("Unexpected trailing content at offset ${cursor.position}")
        }
        return value
    }

    private class Cursor(
        private val text: String,
    ) {
        var position: Int = 0
            private set

        fun isAtEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (!isAtEnd() && text[position].isWhitespace()) position++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            if (isAtEnd()) throw JsonParseException("Unexpected end of input")
            return when (text[position]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.JsonString(parseStringLiteral())
                't' -> parseLiteral("true", JsonValue.JsonBoolean(true))
                'f' -> parseLiteral("false", JsonValue.JsonBoolean(false))
                'n' -> parseLiteral("null", JsonValue.JsonNull)
                else -> parseNumber()
            }
        }

        private fun parseObject(): JsonValue.JsonObject {
            expect('{')
            val members = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!isAtEnd() && text[position] == '}') {
                position++
                return JsonValue.JsonObject(members)
            }
            while (true) {
                skipWhitespace()
                val key = parseStringLiteral()
                skipWhitespace()
                expect(':')
                members[key] = parseValue()
                skipWhitespace()
                when {
                    !isAtEnd() && text[position] == ',' -> position++
                    !isAtEnd() && text[position] == '}' -> {
                        position++
                        return JsonValue.JsonObject(members)
                    }
                    else -> throw JsonParseException("Expected ',' or '}' at offset $position")
                }
            }
        }

        private fun parseArray(): JsonValue.JsonArray {
            expect('[')
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (!isAtEnd() && text[position] == ']') {
                position++
                return JsonValue.JsonArray(items)
            }
            while (true) {
                items += parseValue()
                skipWhitespace()
                when {
                    !isAtEnd() && text[position] == ',' -> position++
                    !isAtEnd() && text[position] == ']' -> {
                        position++
                        return JsonValue.JsonArray(items)
                    }
                    else -> throw JsonParseException("Expected ',' or ']' at offset $position")
                }
            }
        }

        private fun parseStringLiteral(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (isAtEnd()) throw JsonParseException("Unterminated string literal")
                when (val c = text[position++]) {
                    '"' -> return builder.toString()
                    '\\' -> builder.append(parseEscapedChar())
                    else -> builder.append(c)
                }
            }
        }

        /** `\`直後の1文字を読み、対応する実際の文字へ変換する（`parseStringLiteral`専用の内部処理）。 */
        private fun parseEscapedChar(): Char {
            if (isAtEnd()) throw JsonParseException("Unterminated escape sequence")
            return when (val c = text[position++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'n' -> '\n'
                't' -> '\t'
                'r' -> '\r'
                'b' -> '\b'
                'f' -> '\u000C'
                'u' -> {
                    val hex = text.substring(position, position + HEX_DIGITS)
                    position += HEX_DIGITS
                    hex.toInt(HEX_RADIX).toChar()
                }
                else -> throw JsonParseException("Invalid escape sequence: \\$c")
            }
        }

        private fun parseNumber(): JsonValue.JsonNumber {
            val start = position
            if (!isAtEnd() && text[position] == '-') position++
            position = digitsEndIndex(text, position)
            if (!isAtEnd() && text[position] == '.') {
                position++
                position = digitsEndIndex(text, position)
            }
            if (!isAtEnd() && (text[position] == 'e' || text[position] == 'E')) {
                position++
                if (!isAtEnd() && (text[position] == '+' || text[position] == '-')) position++
                position = digitsEndIndex(text, position)
            }
            if (position == start) throw JsonParseException("Invalid number literal at offset $position")
            return JsonValue.JsonNumber(text.substring(start, position).toDouble())
        }

        private fun <T : JsonValue> parseLiteral(
            literal: String,
            value: T,
        ): T {
            if (!text.startsWith(literal, position)) {
                throw JsonParseException("Expected literal '$literal' at offset $position")
            }
            position += literal.length
            return value
        }

        private fun expect(c: Char) {
            if (isAtEnd() || text[position] != c) throw JsonParseException("Expected '$c' at offset $position")
            position++
        }

        companion object {
            private const val HEX_DIGITS = 4
            private const val HEX_RADIX = 16
        }
    }
}

/**
 * `text`の`from`位置から数字が連続する区間の終端indexを返す。トップレベル関数として`Cursor`クラスの外に
 * 置くことで、TooManyFunctions（クラス内の関数数）を増やさずに`parseNumber`の分岐を整理する。
 */
private fun digitsEndIndex(
    text: String,
    from: Int,
): Int {
    var index = from
    while (index < text.length && text[index].isDigit()) index++
    return index
}
