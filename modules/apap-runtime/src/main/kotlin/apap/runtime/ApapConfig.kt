package apap.runtime

import java.nio.file.Files
import java.nio.file.Path

/**
 * 03_基本設計.md 3.15 `application.yaml`の宣言的SPIバインド部分を表す設定値。
 * [routingStrategy]/[retryStrategy]/[cacheStore]/[secretStore]/[compactionStrategy]は、
 * [ApapEngineBuilder.applyConfig]が[ApapEngineBuilder.KNOWN_ROUTING_STRATEGIES]等の既知名表と
 * 突き合わせて対応する組込み実装を選択する。3.15例の値のうち外部システム接続を要するもの
 * （`distributed-kvs`/`vault-compatible`）は接続情報を名前だけで決められないため名前解決の
 * 対象外であり、[ApapEngineBuilder.applyConfig]は未知の名前に対して例外を投げる
 * （黙って既定値へfall backしない）。これらは[ApapConfig]経由ではなく[ApapEngineBuilder]の
 * 対応するメソッド（`cacheStore(...)`/`secretStore(...)`等）へ実装インスタンスを直接渡すこと。
 *
 * 3.15の例はYAMLのネストではなく`apap:`直下にドット区切りの平坦なキーを並べる形式のため、
 * 本実装は最小限の専用パーサ（`apap-plugin`の`PluginManifestParser`と同じ判断: 新規外部依存を
 * 追加しない）で足りる——一般のYAML（リスト・多段ネスト等）は対象外。
 */
data class ApapConfig(
    val routingStrategy: String? = null,
    val retryStrategy: String? = null,
    val cacheStore: String? = null,
    val secretStore: String? = null,
    val compactionStrategy: String? = null,
    val pluginDir: String? = null,
    val pluginSignatureRequired: Boolean = true,
) {
    companion object {
        fun fromMap(map: Map<String, String>): ApapConfig =
            ApapConfig(
                routingStrategy = map["routing.strategy"],
                retryStrategy = map["retry.strategy"],
                cacheStore = map["cache.store"],
                secretStore = map["secret.store"],
                compactionStrategy = map["compaction.strategy"],
                pluginDir = map["plugin.dir"],
                pluginSignatureRequired = map["plugin.signature.required"]?.toBooleanStrictOrNull() ?: true,
            )

        fun fromYamlFile(path: Path): ApapConfig = fromMap(parseFlatApapYaml(Files.readString(path)))

        /**
         * `apap:`直下の`  key.path: value`行のみを対象とする最小限のパーサ。コメント（`#`始まり）と
         * 空行は無視する。
         */
        private fun parseFlatApapYaml(text: String): Map<String, String> {
            val lines = text.lines().map { it.trimEnd() }
            val rootIndex = lines.indexOfFirst { it.trim() == "apap:" }
            if (rootIndex < 0) return emptyMap()
            return lines
                .drop(rootIndex + 1)
                .takeWhile { line -> line.isBlank() || line.startsWith(" ") || line.startsWith("\t") }
                .mapNotNull { line -> parseKeyValueLine(line) }
                .toMap()
        }

        private fun parseKeyValueLine(line: String): Pair<String, String>? {
            val trimmed = line.trim()
            val separatorIndex = trimmed.indexOf(':')
            val isCommentOrBlank = trimmed.isEmpty() || trimmed.startsWith("#")
            return if (isCommentOrBlank || separatorIndex < 0) {
                null
            } else {
                val key = trimmed.substring(0, separatorIndex).trim()
                val value = trimmed.substring(separatorIndex + 1).trim().trim('"', '\'')
                key to value
            }
        }
    }
}
