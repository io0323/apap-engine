package apap.adapter.spi

import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.FinishReason
import apap.domain.model.vo.Usage

/**
 * 03_基本設計.md 3.3.2 `execute(request): AdapterResponse`。
 * 3.3.1 `CanonicalResponse` のうち、コア側が正規化時に付与する情報（resolvedProvider/resolvedModel/
 * cached/cost）はAdapterの関知するところではないため含まない。costはコア側が[Usage]とPriceBookから算出する。
 */
data class AdapterResponse(
    val output: List<ContentPart>,
    val finishReason: FinishReason,
    val usage: Usage,
    val toolCalls: List<ToolCall> = emptyList(),
    val providerRequestId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)
