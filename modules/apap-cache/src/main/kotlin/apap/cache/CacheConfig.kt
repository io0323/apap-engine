package apap.cache

import apap.domain.model.vo.CapabilityId
import java.time.Duration

/**
 * CLAUDE.md不変条件7: 既定値は設定可能。02_システム仕様.md 2.14: Request Cache TTL既定24h、
 * Response Cache TTL既定1h（[responseCacheTtlByCapability]でCapability別に上書き可）。
 */
data class CacheConfig(
    val requestCacheTtl: Duration = Duration.ofHours(DEFAULT_REQUEST_CACHE_TTL_HOURS),
    val responseCacheTtl: Duration = Duration.ofHours(DEFAULT_RESPONSE_CACHE_TTL_HOURS),
    val responseCacheTtlByCapability: Map<CapabilityId, Duration> = emptyMap(),
) {
    init {
        require(!requestCacheTtl.isNegative && !requestCacheTtl.isZero) {
            "requestCacheTtl must be positive: $requestCacheTtl"
        }
        require(!responseCacheTtl.isNegative && !responseCacheTtl.isZero) {
            "responseCacheTtl must be positive: $responseCacheTtl"
        }
        responseCacheTtlByCapability.values.forEach { ttl ->
            require(!ttl.isNegative && !ttl.isZero) { "responseCacheTtlByCapability entries must be positive: $ttl" }
        }
    }

    fun responseCacheTtlFor(capabilityId: CapabilityId): Duration {
        val override = responseCacheTtlByCapability[capabilityId]
        return override ?: responseCacheTtl
    }

    private companion object {
        const val DEFAULT_REQUEST_CACHE_TTL_HOURS = 24L
        const val DEFAULT_RESPONSE_CACHE_TTL_HOURS = 1L
    }
}
