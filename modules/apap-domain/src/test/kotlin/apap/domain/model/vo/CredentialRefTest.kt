package apap.domain.model.vo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CredentialRefTest {
    private fun ref(state: CredentialState) = CredentialRef("secret-ref", 1, state)

    @Test
    fun `rejects blank secretRef`() {
        assertThrows(IllegalArgumentException::class.java) { CredentialRef("", 1, CredentialState.STANDBY) }
    }

    @Test
    fun `rejects non positive version`() {
        assertThrows(IllegalArgumentException::class.java) { CredentialRef("secret-ref", 0, CredentialState.STANDBY) }
    }

    @Test
    fun `allows STANDBY to ACTIVE`() {
        assertEquals(CredentialState.ACTIVE, ref(CredentialState.STANDBY).transitionTo(CredentialState.ACTIVE).state)
    }

    @Test
    fun `allows STANDBY to REVOKED on verification failure`() {
        assertEquals(CredentialState.REVOKED, ref(CredentialState.STANDBY).transitionTo(CredentialState.REVOKED).state)
    }

    @Test
    fun `allows ACTIVE to REVOKED_PENDING`() {
        assertEquals(
            CredentialState.REVOKED_PENDING,
            ref(CredentialState.ACTIVE).transitionTo(CredentialState.REVOKED_PENDING).state,
        )
    }

    @Test
    fun `allows REVOKED_PENDING to REVOKED`() {
        assertEquals(
            CredentialState.REVOKED,
            ref(CredentialState.REVOKED_PENDING).transitionTo(CredentialState.REVOKED).state,
        )
    }

    @Test
    fun `rejects ACTIVE directly to REVOKED, skipping REVOKED_PENDING (ADR-0008)`() {
        assertThrows(IllegalCredentialStateTransitionException::class.java) {
            ref(CredentialState.ACTIVE).transitionTo(CredentialState.REVOKED)
        }
    }

    @Test
    fun `rejects transition out of terminal REVOKED`() {
        assertThrows(IllegalCredentialStateTransitionException::class.java) {
            ref(CredentialState.REVOKED).transitionTo(CredentialState.STANDBY)
        }
    }

    @Test
    fun `rejects skipping STANDBY straight to REVOKED_PENDING`() {
        assertThrows(IllegalCredentialStateTransitionException::class.java) {
            ref(CredentialState.STANDBY).transitionTo(CredentialState.REVOKED_PENDING)
        }
    }
}
