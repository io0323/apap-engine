package apap.domain.model.provider

import apap.domain.model.IllegalStateTransitionException
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.SemVer

/** 09_状態遷移図.md 9.1。 */
enum class ProviderStatus { REGISTERED, VALIDATING, ACTIVE, SUSPENDED, DRAINING, DISABLED, DELETED }

/** 02_システム仕様.md 2.6.3: Provider Healthの判定結果（UP/DEGRADED/DOWN）。 */
enum class ProviderHealthStatus { UP, DEGRADED, DOWN }

class IllegalProviderStateTransitionException(
    from: ProviderStatus,
    to: ProviderStatus,
) : IllegalStateTransitionException("Illegal Provider transition: $from -> $to")

/** 02_システム仕様.md 2.6.2: rate_limits{rpm, tpm, concurrent}。 */
data class RateLimits(
    val rpm: Int,
    val tpm: Int,
    val concurrent: Int,
) {
    init {
        require(rpm > 0) { "rpm must be positive: $rpm" }
        require(tpm > 0) { "tpm must be positive: $tpm" }
        require(concurrent > 0) { "concurrent must be positive: $concurrent" }
    }
}

/** 04_ドメイン設計.md 4.3.1 Endpoint（Entity）: regionはProvider.regions内。 */
data class Endpoint(
    val endpointId: String,
    val region: Region,
    val baseUrl: String,
    val weight: Int,
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(weight > 0) { "weight must be positive: $weight" }
    }
}

/**
 * 04_ドメイン設計.md 4.3.1 Provider Aggregate（Root）。
 * 状態遷移は09_状態遷移図.md 9.1のみ許可。ACTIVE化にはVALIDATING合格が必要。
 * credentialRefは最低1つ、ACTIVEは常に1つ以下（ADR-0008の4状態モデルでも維持）。
 */
data class Provider(
    val providerId: ProviderId,
    val name: String,
    val adapterPluginId: String,
    val spiVersion: SemVer,
    val endpoints: List<Endpoint>,
    val authType: String,
    val credentialRefs: List<CredentialRef>,
    val rateLimits: RateLimits,
    val priority: Int,
    val regions: Set<Region>,
    val status: ProviderStatus = ProviderStatus.REGISTERED,
    val tags: Set<String> = emptySet(),
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(authType.isNotBlank()) { "authType must not be blank" }
        require(priority in MIN_PRIORITY..MAX_PRIORITY) { "priority must be within 1..100: $priority" }
        require(credentialRefs.isNotEmpty()) { "Provider must have at least one CredentialRef" }
        require(credentialRefs.count { it.state == CredentialState.ACTIVE } <= 1) {
            "Provider must have at most one ACTIVE CredentialRef at a time"
        }
        endpoints.forEach { endpoint ->
            require(endpoint.region in regions) {
                "Endpoint region must be one of Provider.regions: ${endpoint.region} not in $regions"
            }
        }
    }

    fun transitionTo(target: ProviderStatus): Provider {
        val allowed = ALLOWED_TRANSITIONS[status].orEmpty()
        if (target !in allowed) {
            throw IllegalProviderStateTransitionException(status, target)
        }
        return copy(status = target)
    }

    fun withCredentialRefs(newCredentialRefs: List<CredentialRef>): Provider = copy(credentialRefs = newCredentialRefs)

    companion object {
        // 9.1: REGISTERED->VALIDATING->ACTIVE⇄SUSPENDED、ACTIVE->DRAINING->DISABLED->(論理)DELETED
        //      VALIDATING->REGISTERED(検証失敗)、DISABLED->VALIDATING(再有効化)
        private val ALLOWED_TRANSITIONS: Map<ProviderStatus, Set<ProviderStatus>> =
            mapOf(
                ProviderStatus.REGISTERED to setOf(ProviderStatus.VALIDATING),
                ProviderStatus.VALIDATING to setOf(ProviderStatus.ACTIVE, ProviderStatus.REGISTERED),
                ProviderStatus.ACTIVE to setOf(ProviderStatus.SUSPENDED, ProviderStatus.DRAINING),
                ProviderStatus.SUSPENDED to setOf(ProviderStatus.ACTIVE),
                ProviderStatus.DRAINING to setOf(ProviderStatus.DISABLED),
                ProviderStatus.DISABLED to setOf(ProviderStatus.VALIDATING, ProviderStatus.DELETED),
                ProviderStatus.DELETED to emptySet(),
            )

        private const val MIN_PRIORITY = 1
        private const val MAX_PRIORITY = 100
    }
}
