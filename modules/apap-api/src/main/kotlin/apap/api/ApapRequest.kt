package apap.api

import apap.domain.model.execution.GenerationParams
import apap.domain.model.execution.InputMessage
import apap.domain.model.execution.ToolDefinition
import apap.domain.model.execution.ToolResult
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ConversationId
import apap.domain.model.vo.SessionId
import apap.domain.model.vo.TenantId
import java.time.Duration

/**
 * [apap.runtime.ApapEngine.execute]/[apap.runtime.ApapEngine.executeStream]の公開入力型。
 *
 * `apap.domain.model.execution.CanonicalRequest`（内部表現）とほぼ同じ構造だが、意図的に別の型として
 * 持つ（`requestId`/`traceId`を省略可能にする——未指定ならEngine側の`IdGenerator`が発行する、埋込ホストが
 * 自前のtraceIdをW3C Trace Context相当で伝播したい場合のみ指定すればよい）。CLAUDE.md不変条件3
 * （公開APIにProvider/Model物理名を露出しない）により、利用側が指定できるのは[capabilityId]と
 * [modelAlias]のみで、Provider/Model IDを直接指定するフィールドは存在しない。
 *
 * [ContentPart]/[GenerationParams]/[ToolDefinition]/[TenantId]等はapap-domainのVOをそのまま用いる
 * （要件充足に影響しない実装判断。docs/integration/prompt-engine.mdのバージョニング方針を参照:
 * 将来これらのVOに破壊的変更が入ると、ADR-0016のような明示的なSPI境界が無い現状ではapap-apiの
 * 破壊的変更としても波及しうる、という現時点の制約として記録する）。
 */
data class ApapRequest(
    val tenantId: TenantId,
    val principal: String,
    val capabilityId: CapabilityId,
    val input: List<ContentPart>,
    val modelAlias: String? = null,
    val params: GenerationParams = GenerationParams(),
    val tools: List<ToolDefinition>? = null,
    /**
     * 直前の応答で返った`toolCalls`に対する実行結果（05_シーケンス設計.md 5.4後半）。
     * Toolの実行自体は利用側の責務で、APAPは結果をProviderへ中継するだけ。
     */
    val toolResults: List<ToolResult> = emptyList(),
    val outputSchema: String? = null,
    val conversationId: ConversationId? = null,
    val sessionId: SessionId? = null,
    val idempotencyKey: String? = null,
    val timeoutBudget: Duration = DEFAULT_TIMEOUT_BUDGET,
    val requestId: String? = null,
    val traceId: String? = null,
    /**
     * role付きの発話列（13.2 `messages[]`）。既定は[input]を単一USER発話とみなしたもの。
     * **System Promptを効かせるにはこちらを使うこと**——[input]だけではroleを表現できず、
     * Providerからはすべてユーザ発話に見える（P11-F4 / ADR-0031）。
     */
    val messages: List<InputMessage> = InputMessage.userOnly(input),
) {
    private companion object {
        val DEFAULT_TIMEOUT_BUDGET: Duration = Duration.ofSeconds(30)
    }
}
