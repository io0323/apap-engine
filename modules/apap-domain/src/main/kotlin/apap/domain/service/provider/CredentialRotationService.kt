package apap.domain.service.provider

import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import java.time.Duration
import java.time.Instant

data class CredentialRotationResult(
    val newCredential: CredentialRef,
    val oldCredential: CredentialRef?,
)

/**
 * 04_ドメイン設計.md 4.6: 新旧世代の検証付き切替手順。
 * ADR-0008により9.7の4状態を正とするため、旧ACTIVEは即REVOKEDではなくREVOKED_PENDINGへ遷移する
 * （猶予期間中の送信中リクエストがAUTH_ERRORで失敗し、無用なFallbackが多発することを防ぐ）。
 */
object CredentialRotationService {
    private const val DEFAULT_GRACE_PERIOD_HOURS = 24L

    /**
     * 新Credential(STANDBY)の検証結果を受けて切り替える。
     * 合格: 新→ACTIVE、旧ACTIVE→REVOKED_PENDING。不合格: 新→REVOKED、旧は変更しない。
     */
    fun rotate(
        newCredential: CredentialRef,
        oldActiveCredential: CredentialRef?,
        verified: Boolean,
    ): CredentialRotationResult {
        require(newCredential.state == CredentialState.STANDBY) {
            "newCredential must be STANDBY to rotate: ${newCredential.state}"
        }
        oldActiveCredential?.let {
            require(it.state == CredentialState.ACTIVE) { "oldActiveCredential must be ACTIVE: ${it.state}" }
        }

        return if (verified) {
            val promoted = newCredential.transitionTo(CredentialState.ACTIVE)
            val demotedOld = oldActiveCredential?.transitionTo(CredentialState.REVOKED_PENDING)
            CredentialRotationResult(promoted, demotedOld)
        } else {
            CredentialRotationResult(newCredential.transitionTo(CredentialState.REVOKED), oldActiveCredential)
        }
    }

    /** 猶予期間（既定24h、9.7）経過後、REVOKED_PENDINGをREVOKEDへ完了させる。 */
    fun completeRevocation(
        credential: CredentialRef,
        revokedPendingSince: Instant,
        now: Instant,
        gracePeriod: Duration = Duration.ofHours(DEFAULT_GRACE_PERIOD_HOURS),
    ): CredentialRef {
        require(credential.state == CredentialState.REVOKED_PENDING) {
            "credential must be REVOKED_PENDING: ${credential.state}"
        }
        require(!now.isBefore(revokedPendingSince.plus(gracePeriod))) {
            "grace period has not elapsed yet: now=$now, eligibleAt=${revokedPendingSince.plus(gracePeriod)}"
        }
        return credential.transitionTo(CredentialState.REVOKED)
    }
}
