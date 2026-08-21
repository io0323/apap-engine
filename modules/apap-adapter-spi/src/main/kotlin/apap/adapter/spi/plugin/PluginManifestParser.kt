package apap.adapter.spi.plugin

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.SemVer

class PluginManifestParseException(
    message: String,
) : Exception(message)

/**
 * 15_Provider追加手順.md 15.1 Step2 `plugin.yaml` のパーサ。
 *
 * `plugin.yaml` は常にフラットな `key: value` / `key: [a, b, c]` の並びのみで構成される
 * （ネストしたマッピングやリテラルブロックは持たない）ため、フル機能のYAML実装を依存に加えず、
 * この既知のスキーマだけを読める最小限のパーサとして自前実装する
 * （CLAUDE.md「ADR化するか否かの判断基準」: 依存選定の詳細でありFR/NFRの充足には影響しないためADR化しない）。
 */
object PluginManifestParser {
    fun parse(yaml: String): PluginManifest {
        val fields = parseFlatMapping(yaml)

        fun required(key: String): String =
            fields[key]
                ?: throw PluginManifestParseException("plugin.yaml is missing required field: $key")

        return PluginManifest(
            pluginId = required("plugin_id"),
            version = SemVer.parse(required("version")),
            spiVersionRange = SemVerRange.parse(required("spi_version")),
            entryPoint = required("entry_point"),
            capabilities = parseList(required("capabilities")).map { CapabilityId(it) }.toSet(),
            authTypes = parseList(required("auth_types")).toSet(),
            signature = required("signature"),
        )
    }

    private fun parseFlatMapping(yaml: String): Map<String, String> =
        yaml
            .lineSequence()
            .map { stripComment(it).trim() }
            .filter { it.isNotEmpty() }
            .associate { line -> parseKeyValue(line) }

    private fun parseKeyValue(line: String): Pair<String, String> {
        val separatorIndex = line.indexOf(':')
        if (separatorIndex <= 0) {
            throw PluginManifestParseException("Invalid plugin.yaml line (expected 'key: value'): $line")
        }
        val key = line.substring(0, separatorIndex).trim()
        val value = unquote(line.substring(separatorIndex + 1).trim())
        return key to value
    }

    private fun stripComment(line: String): String {
        val hashIndex = line.indexOf('#')
        return if (hashIndex >= 0) line.substring(0, hashIndex) else line
    }

    private fun unquote(value: String): String =
        if (isWrappedIn(value, '"') || isWrappedIn(value, '\'')) {
            value.substring(1, value.length - 1)
        } else {
            value
        }

    private fun isWrappedIn(
        value: String,
        quote: Char,
    ): Boolean = value.length >= 2 && value.first() == quote && value.last() == quote

    private fun parseList(value: String): List<String> {
        if (!value.startsWith("[") || !value.endsWith("]")) {
            throw PluginManifestParseException("Expected a flow-sequence (e.g. [a, b]) but got: $value")
        }
        return value
            .substring(1, value.length - 1)
            .split(",")
            .map { unquote(it.trim()) }
            .filter { it.isNotEmpty() }
    }
}
