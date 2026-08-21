package apap.routing.spi

import apap.domain.service.routing.Candidate
import apap.domain.service.routing.RoutingDomainService
import apap.domain.service.routing.ScoredCandidate

/**
 * 03_基本設計.md 3.3.4 / 16_拡張ポイント.md 16.2: 負荷分散戦略の差替可能SPI。
 * 既定実装[WeightedRoundRobinLoadBalancer]は[RoutingDomainService.selectViaLoadBalancing]
 * （2.5.2 step5）を呼ぶだけ。乱数源（`randomValue`）はDomain層がMath.random()を直接呼ばない方針
 * （CLAUDE.md 実装規約）に合わせ、apap-routing側で注入する。
 */
interface LoadBalancer {
    fun choose(
        scored: List<ScoredCandidate>,
        randomValue: Double,
    ): Candidate
}

class WeightedRoundRobinLoadBalancer : LoadBalancer {
    override fun choose(
        scored: List<ScoredCandidate>,
        randomValue: Double,
    ): Candidate = RoutingDomainService.selectViaLoadBalancing(scored, randomValue)
}
