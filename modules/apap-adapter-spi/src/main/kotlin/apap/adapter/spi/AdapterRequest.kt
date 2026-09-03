package apap.adapter.spi

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import java.time.Duration

/**
 * 03_基本設計.md 3.3.2 `AdapterRequest`。
 *
 * `input` は [ContentPart]（apap-domain 4.4）のリストとして表現する。設計書3.3.1の
 * `CapabilityInput`（Capabilityスキーマ準拠の全capability向けUnion）はまだapap-domainに定義されておらず、
 * Adapter SPI契約を先に固めるこの段階では、既存のVendor Neutralなchat向け表現（text/image/audio/video/json）を
 * そのまま流用する。将来`CapabilityInput`が定義された際のマッピング方式は、要件充足に影響する詳細ではないため
 * ADR化せず、この段階の判断としてここに記す（CLAUDE.md「ADR化するか否かの判断基準」）。
 */
data class AdapterRequest(
    val capabilityId: CapabilityId,
    val modelName: String,
    val input: List<ContentPart>,
    val params: GenerationParams = GenerationParams(),
    val tools: List<ToolDefinition>? = null,
    /**
     * Tool実行結果（5.4後半）。既定は空なので、既存Adapterはこのフィールドを無視しても
     * コンパイル・動作とも影響を受けない（ADR-0016: 省略可能フィールドの追加はマイナー更新）。
     */
    val toolResults: List<ToolResult> = emptyList(),
    val outputSchema: String? = null,
    val timeout: Duration,
    val traceHeaders: Map<String, String> = emptyMap(),
    val authContext: AuthContext,
) {
    init {
        require(modelName.isNotBlank()) { "AdapterRequest.modelName must not be blank" }
        require(!timeout.isNegative && !timeout.isZero) { "AdapterRequest.timeout must be positive: $timeout" }
    }
}
