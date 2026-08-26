package apap.execution.attempt

import apap.adapter.spi.AdapterResponse
import apap.domain.model.execution.ProcessedPrompt
import apap.domain.model.vo.NormalizedError
import apap.domain.service.routing.Candidate

/** 03_基本設計.md 3.3.5 `AttemptExecutor.execute(candidate, prompt, req): AttemptResult`。 */
sealed interface AttemptResult {
    data class Success(
        val response: AdapterResponse,
        val prompt: ProcessedPrompt,
        /** 実際に成功した候補。ResponseMapperのresolvedProvider/resolvedModelに使う。 */
        val candidate: Candidate,
        /**
         * 成功に至るまでの1候補内の試行回数（[AttemptExecutor]が集計）。既定1（初回で成功）。
         * `RequestCompleted{retries}`用（Audit Engine向け、[apap.domain.event.RequestCompleted]参照）。
         */
        val attempts: Int = 1,
        /** 成功に至るまでにFallbackEngineが経由した段数（[Failure.fallbacks]と同じ集計元）。 */
        val fallbacks: Int = 0,
    ) : AttemptResult

    data class Failure(
        val error: NormalizedError,
        val attempts: Int,
        /**
         * [apap.execution.fallback.FallbackEngine]がChain全体で経由したFallback段数。
         * [apap.execution.attempt.AttemptExecutor]自身は1候補内の試行のみを見るため常に0を返し、
         * FallbackEngineが集計してこの値を上書きする（`RequestFailed{attempts, fallbacks}`用）。
         */
        val fallbacks: Int = 0,
    ) : AttemptResult
}
