package apap.prompt

import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.ProcessedPrompt
import org.slf4j.LoggerFactory

/**
 * 03_基本設計.md 3.3.6 `PromptEngine`: Prompt Pipeline（Validation→Optimization→Rendering）。
 * 本フェーズ（実行エンジン）はPrompt Engine自体（P6）を対象外とし、apap-executionが依存する口として
 * このPortのみを定義する。
 */
interface PromptEngine {
    val isStub: Boolean get() = false

    fun process(request: CanonicalRequest): ProcessedPrompt
}

/**
 * P6未着手のためのパススルー実装: Validation/Optimization/Renderingを一切行わず、入力をそのまま
 * [ProcessedPrompt]化する。`apap-routing`の`ZeroCostEstimator`と同じ「動いているように見えるが
 * 性質を満たさない」失敗モードを避けるため、[optedIn]の明示的な`true`指定を必須とし
 * （指定なしは構築時に例外）、構築時にもWARNログを出す。
 */
class PassthroughPromptEngine(
    optedIn: Boolean,
) : PromptEngine {
    override val isStub: Boolean = true

    init {
        require(optedIn) {
            "PassthroughPromptEngine requires explicit opt-in (optedIn=true). " +
                "Prompt Validation/Optimization/Rendering (P6) is not implemented; " +
                "wiring this without acknowledging the gap is not allowed."
        }
        logger.warn(
            "PassthroughPromptEngine is wired in — Prompt Validation/Optimization/Rendering (P6) " +
                "is not implemented. Input is forwarded unmodified.",
        )
    }

    override fun process(request: CanonicalRequest): ProcessedPrompt = ProcessedPrompt(input = request.input)

    private companion object {
        val logger = LoggerFactory.getLogger(PassthroughPromptEngine::class.java)
    }
}
