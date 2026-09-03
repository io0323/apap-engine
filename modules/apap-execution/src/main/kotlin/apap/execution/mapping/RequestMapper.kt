package apap.execution.mapping

import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.AuthContext
import apap.domain.model.conversation.TurnRole
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.InputMessage
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.ContentPart
import java.time.Duration

/**
 * 03_基本設計.md 3.3.6 `RequestMapper.map(prompt, candidate, req): AdapterRequest`。
 *
 * `CapabilityId`/`ContentPart`/`FinishReason`/`Usage`/`TokenCount`等はapap-adapter-spiが
 * ADR-0016のtypealiasでapap-domainの型をそのまま再エクスポートしているため変換不要（実行時コスト
 * ゼロで代入可能）。`GenerationParams`/`ToolDefinition`は、apap-domainがapap-adapter-spiへ依存できない
 * ため各層が別クラスとして持つ値（`CanonicalRequest.kt`のKDoc参照）であり、ここでフィールド単位に
 * 変換する。
 */
object RequestMapper {
    fun map(
        prompt: ProcessedPrompt,
        req: CanonicalRequest,
        modelName: String,
        authContext: AuthContext,
        timeout: Duration,
    ): AdapterRequest =
        AdapterRequest(
            capabilityId = req.capabilityId,
            modelName = modelName,
            input = prompt.input,
            messages = prompt.messages,
            params = mapParams(req.params),
            tools = req.tools?.map(::mapTool),
            toolResults = req.toolResults.map(::mapToolResult),
            outputSchema = req.outputSchema,
            timeout = timeout,
            traceHeaders = mapOf(TRACE_HEADER to req.traceId),
            authContext = authContext,
        )

    private fun mapToolResult(result: apap.domain.model.execution.ToolResult) =
        apap.adapter.spi.ToolResult(callId = result.callId, content = result.content, isError = result.isError)

    /**
     * ADR-0011 決定5: 是正時のプロンプトにSchema違反の具体的内容を追記する
     * （同一プロンプトの単純再送は無意味なため）。
     */
    fun withCorrectionNote(
        prompt: ProcessedPrompt,
        violationDetail: String,
    ): ProcessedPrompt {
        val note =
            ContentPart.Text(
                "The previous response violated the required output schema: $violationDetail. " +
                    "Correct the response so it strictly conforms to the schema.",
            )
        // `input`だけに足すとAdapterへは届かない——Provider固有形式への変換は`messages`を読むため
        // （AdapterRequest.messagesのKDoc）。是正指示が届かなければ同一プロンプトの単純再送になり、
        // ADR-0011 決定5が成り立たない（P14のリクエスト忠実性検査で検出）。
        // roleはUSER: 利用側からの新しい指示であり、SYSTEM（＝利用側が設定した前提）ではない。
        return prompt.copy(
            input = prompt.input + note,
            messages = prompt.messages + InputMessage(TurnRole.USER, listOf(note)),
        )
    }

    private fun mapParams(p: apap.domain.model.execution.GenerationParams): apap.adapter.spi.GenerationParams =
        apap.adapter.spi.GenerationParams(
            temperature = p.temperature,
            maxTokens = p.maxTokens,
            topP = p.topP,
            stop = p.stop,
            seed = p.seed,
        )

    private fun mapTool(t: apap.domain.model.execution.ToolDefinition): apap.adapter.spi.ToolDefinition =
        apap.adapter.spi.ToolDefinition(
            name = t.name,
            description = t.description,
            parametersSchema = t.parametersSchema,
        )

    private const val TRACE_HEADER = "traceparent"
}
