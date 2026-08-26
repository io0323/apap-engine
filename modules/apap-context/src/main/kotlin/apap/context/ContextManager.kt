package apap.context

import apap.domain.model.conversation.Conversation
import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ModelId
import apap.domain.service.conversation.AssembledContext

/**
 * 03_基本設計.md 3.3.6 `ContextManager`。P5は`refit`のみを対象とし`build`を意図的に除外していた
 * （Conversation Context側の実装、本フェーズ待ち——`apap.execution.fallback.FallbackEngine`が既に
 * `refit`を呼び出している）。
 *
 * [build]の引数は3.3.6の`build(req, conversation): AssembledContext`から2点拡張している
 * （KDoc根拠、要件充足に影響しない実装判断のためADR化せず、CLAUDE.mdの基準に従いここに記す）:
 * - [systemPrompt]: Context ManagerはSystem Prompt自体を生成しない（Prompt/Template側の責務、
 *   `apap.prompt.PromptRenderer`参照）ため、呼び出し側が組立済みのものを渡す。
 * - [modelId]: 02_システム仕様.md 2.8は「Session/Conversation解決」（step2）が「Routing Engine」
 *   （step6、Model確定）より前にあり、bare 2引数のシグネチャでは`contextWindow`の参照元Modelを
 *   決定できない。呼び出し側が明示的に指定する。
 */
interface ContextManager {
    /** ADR-0023: [QueryEmbedder.embed]がsuspendのAdapter呼出を要しうるため`suspend`とする。 */
    suspend fun build(
        request: CanonicalRequest,
        systemPrompt: List<ContentPart>,
        conversation: Conversation?,
        modelId: ModelId,
    ): AssembledContext

    fun refit(
        prompt: ProcessedPrompt,
        modelId: ModelId,
    ): ProcessedPrompt
}
