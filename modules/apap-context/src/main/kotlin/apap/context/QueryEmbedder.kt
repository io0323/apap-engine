package apap.context

import apap.domain.model.vo.ContentPart
import org.slf4j.LoggerFactory

/** 02_システム仕様.md 2.17: 今回入力をMemory類似検索用のベクトルへ変換する。 */
fun interface QueryEmbedder {
    fun embed(parts: List<ContentPart>): List<Double>
}

/**
 * 実ベクトル化（P7以降）未着手のためのパススルー実装: 常に空ベクトルを返し、[DefaultContextManager]は
 * これを「Memory注入なし」として扱う。`apap.prompt.PassthroughPromptEngine`（P5当時）と同じ方針:
 * [optedIn]の明示的な`true`指定を必須とし、構築時にWARNログを出す。ExecutionEngineComposerは
 * 既定でこの実装を`optedIn=true`として配線する（`apap.routing.ZeroCostEstimator`と同じく、
 * 「未接続であることが常に明示されている既定」であり、`optInToStubs`全体のゲートとは別軸）。
 */
class NoOpQueryEmbedder(
    optedIn: Boolean,
) : QueryEmbedder {
    init {
        require(optedIn) {
            "NoOpQueryEmbedder requires explicit opt-in (optedIn=true). " +
                "Memory injection's vector embedding is not implemented; " +
                "wiring this without acknowledging the gap is not allowed."
        }
        logger.warn(
            "NoOpQueryEmbedder is wired in — Memory injection will never find any results " +
                "(query embedding always returns an empty vector).",
        )
    }

    override fun embed(parts: List<ContentPart>): List<Double> = emptyList()

    private companion object {
        val logger = LoggerFactory.getLogger(NoOpQueryEmbedder::class.java)
    }
}
