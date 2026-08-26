package apap.context

import apap.domain.model.vo.ContentPart
import org.slf4j.LoggerFactory

/**
 * 02_システム仕様.md 2.17: 今回入力をMemory類似検索用のベクトルへ変換する。
 *
 * ADR-0023: 実装（P8以降）はProvider Adapterへのsuspend I/O呼出を要するため`suspend`とする
 * （ADR-0022時点では非suspendのままだったが、Circuit Breaker/Rate Limiterを正しく経由する実装は
 * suspend Adapter呼出を避けられないため、この修正で合わせて是正する）。
 */
fun interface QueryEmbedder {
    suspend fun embed(parts: List<ContentPart>): List<Double>
}

/**
 * 実ベクトル化（P8以降、実装位置とResilience機構の使用方針はADR-0023で決定済み）未着手の
 * ためのパススルー実装: 常に空ベクトルを返し、[DefaultContextManager]はこれを「Memory注入なし」
 * として扱う。`apap.prompt.PassthroughPromptEngine`（P5当時）と同じ方針: [optedIn]の明示的な
 * `true`指定を必須とし、構築時にWARNログを出す。ExecutionEngineComposerは既定でこの実装を
 * `optedIn=true`として配線する（`apap.routing.ZeroCostEstimator`と同じく、
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

    override suspend fun embed(parts: List<ContentPart>): List<Double> = emptyList()

    private companion object {
        val logger = LoggerFactory.getLogger(NoOpQueryEmbedder::class.java)
    }
}
