package apap.domain.model.execution

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.RoutingConstraints
import apap.domain.model.vo.RoutingPreferences
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import java.time.Duration

/**
 * 03_基本設計.md 3.3.1 GenerationParams。[CanonicalRequest.params] 専用。Adapter層の
 * `apap.adapter.spi.GenerationParams` とは、apap-domainがapap-adapter-spiへ依存できない
 * （01_CLAUDE.md 不変条件2: 依存は内向き一方向）ため意図的に別クラスとする。フィールド単位の変換は
 * RequestMapper（apap-execution）が担う。
 */
data class GenerationParams(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val stop: List<String> = emptyList(),
    val seed: Long? = null,
)

/**
 * 03_基本設計.md 3.3.1 ToolDefinition。[CanonicalRequest.tools] 専用（[GenerationParams] と同じ理由で
 * Adapter層版とは別クラス）。
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

/**
 * 03_基本設計.md 3.3.1 `CanonicalRequest`: 利用側入力の正規化後の内部表現。
 *
 * `input`/`outputSchema` の型は、Adapter SPI契約（`apap.adapter.spi.AdapterRequest`）が既に
 * 採用している判断（3.3.1の`CapabilityInput`/JsonSchema型は未定義のため`List<ContentPart>`/
 * `String?`で代替する、AdapterRequest.ktのKDoc参照）にこのDomain型でも合わせる。
 * `principal`（3.3.1では`PrincipalId`）は04_ドメイン設計.md 4.4のVO一覧に型定義がなく、
 * CIAP subjectの型付けはFR-SEC-003（本フェーズ対象外）の範囲のため、暫定的に`String`とする。
 */
data class CanonicalRequest(
    val requestId: RequestId,
    val tenantId: TenantId,
    val principal: String,
    val capabilityId: CapabilityId,
    val modelAlias: String? = null,
    val input: List<ContentPart>,
    val params: GenerationParams = GenerationParams(),
    val tools: List<ToolDefinition>? = null,
    /**
     * 直前の応答で返した[ToolCall]に対する実行結果（5.4後半）。
     * Tool自体の実行はAPAPの対象外で、実行するのは利用側（Agent）。
     */
    val toolResults: List<ToolResult> = emptyList(),
    val outputSchema: String? = null,
    val constraints: RoutingConstraints = RoutingConstraints(),
    val preferences: RoutingPreferences = RoutingPreferences(),
    val conversationId: ConversationId? = null,
    val sessionId: SessionId? = null,
    val idempotencyKey: String? = null,
    val timeoutBudget: Duration,
    val traceId: String,
    /**
     * role付きの発話列（13.2 `messages[]`、ADR-0031）。既定は[input]を単一USER発話とみなしたもの。
     * System Promptや過去のassistant応答を渡すにはこちらを使う。
     */
    val messages: List<InputMessage> = InputMessage.userOnly(input),
) {
    init {
        require(principal.isNotBlank()) { "CanonicalRequest.principal must not be blank" }
        require(input.isNotEmpty()) { "CanonicalRequest.input must not be empty" }
        require(!timeoutBudget.isNegative && !timeoutBudget.isZero) {
            "CanonicalRequest.timeoutBudget must be positive: $timeoutBudget"
        }
        require(traceId.isNotBlank()) { "CanonicalRequest.traceId must not be blank" }
    }
}
