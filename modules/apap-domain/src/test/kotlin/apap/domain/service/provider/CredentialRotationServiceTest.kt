package apap.domain.service.provider

import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class CredentialRotationServiceTest {
    private val oldActive = CredentialRef("old-secret", 1, CredentialState.ACTIVE)
    private val newStandby = CredentialRef("new-secret", 2, CredentialState.STANDBY)

    @Test
    fun `verified rotation promotes new to ACTIVE and demotes old to REVOKED_PENDING`() {
        val result = CredentialRotationService.rotate(newStandby, oldActive, verified = true)
        assertEquals(CredentialState.ACTIVE, result.newCredential.state)
        assertEquals(CredentialState.REVOKED_PENDING, result.oldCredential?.state)
    }

    @Test
    fun `failed verification revokes the new credential and leaves the old one untouched`() {
        val result = CredentialRotationService.rotate(newStandby, oldActive, verified = false)
        assertEquals(CredentialState.REVOKED, result.newCredential.state)
        assertEquals(CredentialState.ACTIVE, result.oldCredential?.state)
    }

    @Test
    fun `rotation is allowed without a prior active credential`() {
        val result = CredentialRotationService.rotate(newStandby, oldActiveCredential = null, verified = true)
        assertEquals(CredentialState.ACTIVE, result.newCredential.state)
        assertEquals(null, result.oldCredential)
    }

    @Test
    fun `rejects a new credential that is not STANDBY`() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialRotationService.rotate(oldActive, null, verified = true)
        }
    }

    @Test
    fun `rejects an old credential that is not ACTIVE`() {
        val revokedPending = CredentialRef("x", 1, CredentialState.REVOKED_PENDING)
        assertThrows(IllegalArgumentException::class.java) {
            CredentialRotationService.rotate(newStandby, revokedPending, verified = true)
        }
    }

    @Test
    fun `completeRevocation transitions REVOKED_PENDING to REVOKED after the grace period`() {
        val pending = CredentialRef("old-secret", 1, CredentialState.REVOKED_PENDING)
        val since = Instant.parse("2026-01-01T00:00:00Z")
        val revoked =
            CredentialRotationService.completeRevocation(pending, since, since.plus(Duration.ofHours(24)))
        assertEquals(CredentialState.REVOKED, revoked.state)
    }

    @Test
    fun `completeRevocation rejects completion before the grace period elapses`() {
        val pending = CredentialRef("old-secret", 1, CredentialState.REVOKED_PENDING)
        val since = Instant.parse("2026-01-01T00:00:00Z")
        assertThrows(IllegalArgumentException::class.java) {
            CredentialRotationService.completeRevocation(pending, since, since.plus(Duration.ofHours(23)))
        }
    }

    @Test
    fun `completeRevocation rejects a credential that is not REVOKED_PENDING`() {
        val since = Instant.parse("2026-01-01T00:00:00Z")
        assertThrows(IllegalArgumentException::class.java) {
            CredentialRotationService.completeRevocation(oldActive, since, since.plus(Duration.ofHours(24)))
        }
    }
}
