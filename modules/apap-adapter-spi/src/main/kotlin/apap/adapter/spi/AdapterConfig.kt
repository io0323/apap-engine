package apap.adapter.spi

import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region

/**
 * 03_基本設計.md 3.3.2 `initialize(config: AdapterConfig, secrets: SecretAccessor)`。
 * `endpoints`/`rateLimits`/`regions` はProvider登録時に検証済みの値
 * （[apap.domain.model.provider.Provider]が保持するものと同じ型）をInfrastructure層がそのまま渡す。
 */
data class AdapterConfig(
    val providerId: ProviderId,
    val endpoints: List<Endpoint>,
    val rateLimits: RateLimits,
    val regions: Set<Region>,
    val options: Map<String, String> = emptyMap(),
) {
    init {
        require(endpoints.isNotEmpty()) { "AdapterConfig.endpoints must not be empty" }
    }
}
