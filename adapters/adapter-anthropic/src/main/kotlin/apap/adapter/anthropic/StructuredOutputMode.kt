package apap.adapter.anthropic

/**
 * FR-CAP-003 Structured Output を Provider へどう伝えるか（ADR-0040）。
 *
 * ## なぜ切り替え可能にするのか
 *
 * 実APIには構造化出力のネイティブ機構 `output_config.format` が**存在する**
 * （出典: https://platform.claude.com/docs/en/api/messages の Body Parameters > output_config、
 * および .../api/beta/messages の `BetaOutputConfig.format`（BetaJSONOutputFormat））。
 * したがって既定は[NATIVE]とする。
 *
 * ただし**`format`オブジェクトの内側の正確な形は公開ドキュメントから確定できておらず、
 * 実APIに対して検証していない**（P16では実API検証を見送ったため）。形が違えば
 * `invalid_request_error` で全てのスキーマ付きリクエストが落ちる。これは
 * 「スキーマが無視される」現状よりも悪い。そのため実APIで弾かれた場合に
 * `AdapterConfig.options["structured_output.mode"] = "prompt"` の1行で
 * [PROMPT] へ退避できるようにしてある。
 *
 * この不確かさは docs/adapter-spi-findings.md の [要実測] として記録している。
 */
enum class StructuredOutputMode {
    /** ネイティブ機構（`output_config.format`）でスキーマを強制する。既定。 */
    NATIVE,

    /** スキーマをsystemプロンプトへ組み込む。ネイティブ機構が使えない場合の退避先。 */
    PROMPT,

    /**
     * 何もしない（P16以前の挙動）。**選ぶとStructured Outputは毎回是正リトライ頼みになる**ため、
     * 比較検証以外では使わないこと。
     */
    OFF,
    ;

    companion object {
        const val OPTION_KEY = "structured_output.mode"

        fun from(value: String?): StructuredOutputMode =
            when (value?.lowercase()) {
                null, "native" -> NATIVE
                "prompt" -> PROMPT
                "off" -> OFF
                // 未知の値を既定へ握り潰すと、設定ミスが「なぜかネイティブで動いている」に化ける。
                else -> throw IllegalArgumentException("unknown $OPTION_KEY: $value (native|prompt|off)")
            }
    }
}
