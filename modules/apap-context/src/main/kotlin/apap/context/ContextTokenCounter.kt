package apap.context

import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.TokenCount
import apap.domain.service.execution.TokenEstimationMode

/**
 * `ContextManager.build`/`refit`（modelId引数付き）が使うトークン計上口。ADR-0009の
 * [TokenEstimationMode]を自己申告する（[apap.domain.service.execution.TokenEstimationService.safetyMarginFor]で
 * 安全マージン切替に使う）。apap-contextはapap-domainのみに依存できる
 * （apap-executionがapap-contextに依存する方向のため、Adapter越しのEXACT推定へは到達できない）。
 * modelId単位で解決するため、コンポジションルート側は`(ModelId) -> ContextTokenCounter`の
 * factoryとして注入する。
 */
interface ContextTokenCounter {
    val mode: TokenEstimationMode

    fun count(parts: List<ContentPart>): TokenCount
}
