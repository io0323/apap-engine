package apap.execution.streaming

import apap.context.ConversationManager
import apap.domain.model.conversation.TurnRole
import apap.domain.model.execution.StreamChunk
import apap.domain.model.execution.StreamChunkType
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import org.slf4j.LoggerFactory

/**
 * 02_システム仕様.md 2.8 step11のStreaming版。[StreamingEngine.normalize]が返す`Flow<StreamChunk>`を
 * 値そのものは変えずに中継しつつ、副作用としてConversation Managerへassistant turnを1件書く
 * （着手前レビュー: Turn永続化がStreamingへ未対応だった問題の解消）。
 *
 * - `MESSAGE_END`到達時（正常終端）: 累積した`CONTENT_DELTA`のテキストを結合し、
 *   `TOOL_CALL_DELTA`は`apap.execution.ExecutionEngine.recordAssistantTurn`と同じ読める形の
 *   ContentPart.Textへ変換して1件のassistant turnとして書く。
 * - 中断（`MESSAGE_END`に到達せずFlowが終了、02_システム仕様.md 2.10の部分応答監査方針）:
 *   蓄積済みの部分内容へ[INTERRUPTED_MARKER]を追加した1件のassistant turnを書く。何も蓄積されて
 *   いない場合は書かない（Turnは空のcontentPartsを持てないため、かつ意味のある監査対象がない）。
 * - 永続化の失敗で下流のFlow収集（既に送出済みのチャンク）へ影響させない（ログのみ、
 *   [apap.execution.ExecutionEngine.persistTurn]と同じ方針）。
 *
 * 呼び出し元は[apap.execution.streaming.StreamingRequestExecutor]（着手前レビューでStreaming
 * request flow全体の配線が完了し、本クラスも実行経路から呼ばれるようになった）。
 */
class StreamingTurnRecorder(
    private val conversationManager: ConversationManager,
) {
    fun record(
        conversationId: ConversationId,
        tenantId: TenantId,
        modelId: ModelId,
        chunks: Flow<StreamChunk>,
    ): Flow<StreamChunk> {
        val accumulator = Accumulator()
        return chunks
            .onEach { chunk -> accumulator.accept(chunk) }
            .onCompletion { persist(conversationId, tenantId, modelId, accumulator) }
    }

    private fun persist(
        conversationId: ConversationId,
        tenantId: TenantId,
        modelId: ModelId,
        accumulator: Accumulator,
    ) {
        val contentParts = accumulator.finalContent()
        if (contentParts.isEmpty()) return
        runCatching {
            conversationManager.appendTurn(
                conversationId,
                tenantId,
                TurnRole.ASSISTANT,
                contentParts,
                modelId,
                accumulator.usage,
            )
        }.onFailure { e ->
            logger.warn(
                "failed to persist streaming assistant turn for conversationId={}: {}",
                conversationId.value,
                e.message,
                e,
            )
        }
    }

    private class Accumulator {
        private val textDeltas = mutableListOf<String>()
        private val otherParts = mutableListOf<ContentPart>()
        private var messageEndSeen = false
        var usage: Usage? = null
            private set

        fun accept(chunk: StreamChunk) {
            when (chunk.type) {
                StreamChunkType.CONTENT_DELTA -> acceptDelta(chunk.delta)
                StreamChunkType.TOOL_CALL_DELTA ->
                    chunk.toolCallDelta?.let { call ->
                        otherParts += ContentPart.Text("[tool_call] ${call.toolName}(${call.arguments})")
                    }
                StreamChunkType.USAGE -> usage = chunk.usage
                StreamChunkType.MESSAGE_END -> messageEndSeen = true
                else -> Unit
            }
        }

        private fun acceptDelta(delta: ContentPart?) {
            when (delta) {
                is ContentPart.Text -> textDeltas += delta.text
                null -> Unit
                else -> otherParts += delta
            }
        }

        fun finalContent(): List<ContentPart> {
            val accumulated = mutableListOf<ContentPart>()
            if (textDeltas.isNotEmpty()) accumulated += ContentPart.Text(textDeltas.joinToString(separator = ""))
            accumulated += otherParts
            if (accumulated.isEmpty()) return emptyList()
            return if (messageEndSeen) accumulated else accumulated + ContentPart.Text(INTERRUPTED_MARKER)
        }
    }

    private companion object {
        const val INTERRUPTED_MARKER = "[interrupted]"
        val logger = LoggerFactory.getLogger(StreamingTurnRecorder::class.java)
    }
}
