package apap.adapter.spi

/**
 * 03_基本設計.md 3.3.2: `translateTools(tools: ToolDefinition[]): ProviderToolFormat` の入力。
 * `parametersSchema` はJSON Schema文字列（Domain層はスキーマの構造検証を行わないため、ここでも文字列のまま扱う）。
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersSchema: String,
) {
    init {
        require(name.isNotBlank()) { "ToolDefinition.name must not be blank" }
    }
}

/** Provider応答内のTool呼出指示を共通形式へ正規化したもの（3.3.1 CanonicalResponse.toolCalls）。 */
data class ToolCall(
    val callId: String,
    val toolName: String,
    val arguments: String,
) {
    init {
        require(callId.isNotBlank()) { "ToolCall.callId must not be blank" }
        require(toolName.isNotBlank()) { "ToolCall.toolName must not be blank" }
    }
}

/**
 * `translateTools` の戻り値。Provider固有のTool表現形式そのもの（例: Provider APIへ送るJSON構造）を
 * コアが型として扱わず不透明に運べるようにするためのラッパー。中身の形式はAdapter実装が決める。
 */
data class ProviderToolFormat(
    val payload: Any,
)
