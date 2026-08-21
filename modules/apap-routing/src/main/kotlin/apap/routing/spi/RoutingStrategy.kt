package apap.routing.spi

import apap.domain.service.routing.Candidate
import apap.domain.service.routing.RoutingDomainService
import apap.domain.service.routing.RoutingWeights
import apap.domain.service.routing.ScoredCandidate

/**
 * 03_基本設計.md 3.3.4 / 16_拡張ポイント.md 16.2: スコアリング戦略の差替可能SPI。
 * 既定実装[WeightedScoreRoutingStrategy]は[RoutingDomainService.computeScores]（2.5.2 step3）を
 * 呼ぶだけで、判断ロジック自体はDomain Serviceのまま再実装しない。
 */
interface RoutingStrategy {
    fun score(
        candidates: List<Candidate>,
        weights: RoutingWeights,
    ): List<ScoredCandidate>
}

class WeightedScoreRoutingStrategy : RoutingStrategy {
    override fun score(
        candidates: List<Candidate>,
        weights: RoutingWeights,
    ): List<ScoredCandidate> = RoutingDomainService.computeScores(candidates, weights)
}
