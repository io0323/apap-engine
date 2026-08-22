package apap.execution.mapping

import apap.adapter.spi.AdapterChunk
import apap.adapter.spi.AdapterChunkType
import apap.adapter.spi.AdapterException
import apap.adapter.spi.AdapterResponse
import apap.domain.model.execution.CanonicalResponse
import apap.domain.model.execution.StreamChunk
import apap.domain.model.execution.StreamChunkType
import apap.domain.model.execution.ToolCall
import apap.domain.model.vo.Cost
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.NormalizedError
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.port.IdGenerator
import apap.domain.service.execution.ErrorClassificationService

/**
 * 03_基本設計.md 3.3.6 `ResponseMapper`。`output`/`finishReason`/`usage`はapap-adapter-spiの
 * typealias（ADR-0016）によりapap-domainの型そのものであるため変換不要。`ToolCall`は
 * apap-domainがapap-adapter-spiへ依存できないため各層が別クラスとして持つ値
 * （CanonicalResponse.ktのKDoc参照）であり、ここでフィールド単位に変換する。
 *
 * Usage未提供時のTokenizer推定（ADR-0009/0010）は、ここではなく事前チェック
 * （ExecutionEngineのQuota予約フェーズ）で行う。`AdapterResponse.usage`は必須項目であり、
 * Adapterが正確な値を持たない場合は`estimated=true`を自ら付与する契約（SPI契約）のため、
 * 本Mapperはそのまま転記するのみで再推定はしない。
 */
object ResponseMapper {
    @Suppress("LongParameterList")
    fun normalize(
        response: AdapterResponse,
        requestId: RequestId,
        cost: Cost,
        resolvedProvider: ProviderId,
        resolvedModel: ModelId,
        idGenerator: IdGenerator,
        cached: Boolean = false,
    ): CanonicalResponse =
        CanonicalResponse(
            responseId = idGenerator.newId(),
            requestId = requestId,
            output = response.output,
            toolCalls = response.toolCalls.map(::mapToolCall).ifEmpty { null },
            finishReason = response.finishReason,
            usage = response.usage,
            cost = cost,
            resolvedProvider = resolvedProvider,
            resolvedModel = resolvedModel,
            cached = cached,
            metadata = response.metadata,
        )

    fun normalizeChunk(chunk: AdapterChunk): StreamChunk =
        StreamChunk(
            type = mapChunkType(chunk.type),
            index = chunk.index,
            delta = chunk.delta,
            toolCallDelta = chunk.toolCallDelta?.let(::mapToolCall),
            usage = chunk.usage,
        )

    fun normalizeError(
        e: AdapterException,
        contentFilteredFallbackAllowed: Boolean = false,
    ): NormalizedError =
        ErrorClassificationService.classify(
            category = e.category,
            message = e.message ?: "adapter error",
            providerDetail = e.providerDetail,
            contentFilteredFallbackAllowed = contentFilteredFallbackAllowed,
            retryAfter = e.retryAfter,
        )

    private fun mapToolCall(t: apap.adapter.spi.ToolCall): ToolCall =
        ToolCall(callId = t.callId, toolName = t.toolName, arguments = t.arguments)

    private fun mapChunkType(t: AdapterChunkType): StreamChunkType =
        when (t) {
            AdapterChunkType.MESSAGE_START -> StreamChunkType.MESSAGE_START
            AdapterChunkType.CONTENT_DELTA -> StreamChunkType.CONTENT_DELTA
            AdapterChunkType.TOOL_CALL_DELTA -> StreamChunkType.TOOL_CALL_DELTA
            AdapterChunkType.USAGE -> StreamChunkType.USAGE
            AdapterChunkType.MESSAGE_END -> StreamChunkType.MESSAGE_END
            AdapterChunkType.ERROR -> StreamChunkType.ERROR
            AdapterChunkType.HEARTBEAT -> StreamChunkType.HEARTBEAT
        }
}
