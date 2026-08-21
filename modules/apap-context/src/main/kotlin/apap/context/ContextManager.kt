package apap.context

import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.ModelId
import org.slf4j.LoggerFactory

/**
 * 03_基本設計.md 3.3.6 `ContextManager`。02_システム仕様.md 2.12「Prompt再構築」: Fallbackで次候補へ
 * 移る前に、その候補Modelのcontext window/形式制約に合わせてPromptを再圧縮する。
 * 本フェーズはapap-executionが依存する`refit`のみを対象とする（`build`は履歴+Memory+入力の合成、
 * Conversation Context側の実装（P6）待ちのため対象外）。
 */
interface ContextManager {
    val isStub: Boolean get() = false

    fun refit(
        prompt: ProcessedPrompt,
        modelId: ModelId,
    ): ProcessedPrompt
}

/**
 * P6未着手のためのパススルー実装: 再圧縮を一切行わず入力をそのまま返す。[optedIn]の明示的な`true`
 * 指定を必須とし、構築時にWARNログを出す（`apap.prompt.PassthroughPromptEngine`と同じ方針）。
 */
class PassthroughContextManager(
    optedIn: Boolean,
) : ContextManager {
    override val isStub: Boolean = true

    init {
        require(optedIn) {
            "PassthroughContextManager requires explicit opt-in (optedIn=true). " +
                "Fallback時のPrompt再圧縮(refit)は次候補のcontext window制約を考慮しないため、" +
                "指定なしでの利用は許可しない。"
        }
        logger.warn(
            "PassthroughContextManager is wired in — refit() does not actually recompress the prompt " +
                "for the next candidate's context window. Fallback to a smaller-context model may fail.",
        )
    }

    override fun refit(
        prompt: ProcessedPrompt,
        modelId: ModelId,
    ): ProcessedPrompt = prompt

    private companion object {
        val logger = LoggerFactory.getLogger(PassthroughContextManager::class.java)
    }
}
