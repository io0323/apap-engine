package apap.domain.model.vo

import apap.domain.model.IllegalStateTransitionException

/** ADR-0008: 09_状態遷移図.md 9.7を正とする4状態モデル。 */
enum class CredentialState { STANDBY, ACTIVE, REVOKED_PENDING, REVOKED }

class IllegalCredentialStateTransitionException(
    from: CredentialState,
    to: CredentialState,
) : IllegalStateTransitionException("Illegal CredentialRef transition: $from -> $to")

/**
 * 04_ドメイン設計.md 4.4: Secret Store参照キー + version。値そのものは絶対に保持しない。
 * 状態はADR-0008により9.7の4状態（STANDBY/ACTIVE/REVOKED_PENDING/REVOKED）を正とする。
 * 「ACTIVEは常に1つ」の不変条件はProvider Aggregate（複数CredentialRef間の整合）が強制する。
 */
data class CredentialRef(
    val secretRef: String,
    val version: Int,
    val state: CredentialState,
) {
    init {
        require(secretRef.isNotBlank()) { "secretRef must not be blank" }
        require(version > 0) { "version must be positive: $version" }
    }

    fun transitionTo(target: CredentialState): CredentialRef {
        val allowed = ALLOWED_TRANSITIONS[state].orEmpty()
        if (target !in allowed) {
            throw IllegalCredentialStateTransitionException(state, target)
        }
        return copy(state = target)
    }

    companion object {
        // 9.7: STANDBY→ACTIVE(→旧ACTIVEはREVOKED_PENDINGへ) / ACTIVE→REVOKED_PENDING /
        //      REVOKED_PENDING→REVOKED / STANDBY→REVOKED(検証失敗)
        private val ALLOWED_TRANSITIONS: Map<CredentialState, Set<CredentialState>> =
            mapOf(
                CredentialState.STANDBY to setOf(CredentialState.ACTIVE, CredentialState.REVOKED),
                CredentialState.ACTIVE to setOf(CredentialState.REVOKED_PENDING),
                CredentialState.REVOKED_PENDING to setOf(CredentialState.REVOKED),
                CredentialState.REVOKED to emptySet(),
            )
    }
}
