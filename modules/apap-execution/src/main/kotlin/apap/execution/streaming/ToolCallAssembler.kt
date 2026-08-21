package apap.execution.streaming

import apap.domain.model.execution.ToolCall

/**
 * 02_システム仕様.md 2.10「ToolCallデルタはStreaming Engineが組立て、完結時に確定イベント」。
 * `callId`単位で分割到着した`arguments`（JSON文字列の断片）を連結し、構造的に完結した時点
 * （最上位オブジェクトの中括弧が閉じた時点）で完成した[ToolCall]を返す。
 *
 * 完結判定はJSON構文の妥当性までは検証しない中括弧の対応関係のみの軽量ヒューリスティックである
 * （文字列リテラル内の`{`/`}`はエスケープ処理込みで除外する）。厳密なJSON検証には専用パーサ
 * （ADR-0017: Jackson）が必要だがapap-executionは現状Jacksonに依存しておらず、FR-RSP-003
 * 「ToolCallの逐次組立」の充足に完全なスキーマ検証までは要求されないため、要件充足に影響しない
 * 実装判断としてこの簡易版を採用する（ADR化せずここに根拠を残す）。
 */
class ToolCallAssembler {
    private val buffers = mutableMapOf<String, StringBuilder>()
    private val toolNames = mutableMapOf<String, String>()

    /** @return 断片を取り込んだ結果、完結したなら組み立て済みの[ToolCall]。未完結ならnull。 */
    fun accept(delta: ToolCall): ToolCall? {
        val buffer = buffers.getOrPut(delta.callId) { StringBuilder() }
        buffer.append(delta.arguments)
        toolNames[delta.callId] = delta.toolName
        if (!isBalanced(buffer)) return null
        val complete = ToolCall(delta.callId, toolNames.getValue(delta.callId), buffer.toString())
        buffers.remove(delta.callId)
        toolNames.remove(delta.callId)
        return complete
    }

    private fun isBalanced(buffer: StringBuilder): Boolean {
        if (buffer.isEmpty() || buffer.first() != '{') return false
        var depth = 0
        var inString = false
        var escaped = false
        for (c in buffer) {
            if (escaped) {
                escaped = false
                continue
            }
            when {
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> depth--
            }
        }
        return depth == 0
    }
}
