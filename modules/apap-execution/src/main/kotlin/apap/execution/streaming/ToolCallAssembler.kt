package apap.execution.streaming

import apap.domain.model.execution.ToolCall

/**
 * 02_システム仕様.md 2.10「ToolCallデルタはStreaming Engineが組立て、完結時に確定イベント」。
 * `callId`単位で分割到着した`arguments`（JSON文字列の断片）を連結し、完結した時点で完成した
 * [ToolCall]を返す。複数の`callId`を独立に並行追跡する（[buffers]/[toolNames]はcallId別）。
 *
 * 完結判定はADR-0019に従い2段構え:
 * 1. **第一候補**（[explicitComplete]）: Adapterが`AdapterChunk.toolCallComplete=true`で
 *    明示した場合はそれを無条件に信頼し、構文チェックをせず完結とみなす。Providerのストリームは
 *    通常tool call境界を明示的に通知するため、これが正しい判定源である。
 * 2. **フォールバック**（[isBalanced]）: 上記シグナルを送らない（未対応）Adapter向けに、
 *    蓄積した引数文字列の中括弧の対応関係（文字列リテラル内の`{`/`}`はエスケープ処理込みで除外）
 *    が取れた時点で完結とみなす軽量ヒューリスティック。厳密なJSON構文検証ではない
 *    （専用パーサ導入はFR-RSP-003の充足に必須ではないため見送り、ADR-0019で判断根拠を記録済み）。
 */
class ToolCallAssembler {
    private val buffers = mutableMapOf<String, StringBuilder>()
    private val toolNames = mutableMapOf<String, String>()

    /**
     * @param explicitComplete Adapterが明示した完結シグナル（`AdapterChunk.toolCallComplete`）。
     * @return 断片を取り込んだ結果、完結したなら組み立て済みの[ToolCall]。未完結ならnull。
     */
    fun accept(
        delta: ToolCall,
        explicitComplete: Boolean = false,
    ): ToolCall? {
        val buffer = buffers.getOrPut(delta.callId) { StringBuilder() }
        buffer.append(delta.arguments)
        toolNames[delta.callId] = delta.toolName
        if (!explicitComplete && !isBalanced(buffer)) return null
        val complete = ToolCall(delta.callId, toolNames.getValue(delta.callId), buffer.toString())
        buffers.remove(delta.callId)
        toolNames.remove(delta.callId)
        return complete
    }

    /** ストリーム終端時に未完結のまま残っているcallIdの集合。空でなければエラーとして扱うこと。 */
    fun pendingCallIds(): Set<String> = buffers.keys.toSet()

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
