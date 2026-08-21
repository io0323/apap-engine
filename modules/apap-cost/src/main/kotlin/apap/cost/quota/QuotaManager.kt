package apap.cost.quota

import apap.domain.model.cost.QuotaPolicy
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import java.time.Duration
import java.time.Instant

/** [QuotaManager]予約決済の状態。CLAUDE.md実装規約: 状態遷移は専用の型で表す。 */
enum class ReservationStatus { PENDING, COMMITTED, RELEASED, EXPIRED }

/**
 * 03_基本設計.md 3.3.6 `QuotaManager.checkAndReserve`の戻り値。02_システム仕様.md 2.8 step7で
 * 発行され、成功時[QuotaManager.commit]・失敗時[QuotaManager.release]で必ず決済されなければならない
 * （P0で保留していたC2/U16の解消: 決済漏れがテナントのQuota枯渇による締め出しに直結するため）。
 */
data class Reservation(
    val reservationId: String,
    val tenantId: TenantId,
    val providerId: ProviderId,
    val modelId: ModelId,
    val estimatedTokens: TokenCount,
    val estimatedCost: Money,
    val createdAt: Instant,
    val expiresAt: Instant,
    val status: ReservationStatus = ReservationStatus.PENDING,
)

/** [QuotaManager.checkAndReserve]が上限超過で拒否した際に送出する。 */
class QuotaExceededException(
    val tenantId: TenantId,
    val quotaId: String,
    val dimension: String,
) : Exception("Quota exceeded for tenant=${tenantId.value}, quota=$quotaId, dimension=$dimension")

/**
 * 未決済のまま滞留した[Reservation]をTTLで解放する操作に対して呼び出し元へ結果を返す
 * （検知可能にする、というタスク要求をログではなく戻り値で満たす）。
 */
data class ExpiredReservation(
    val reservation: Reservation,
    val expiredAt: Instant,
)

/** CLAUDE.md不変条件7: 既定値は設定可能。 */
data class QuotaManagerConfig(
    /**
     * 未決済予約のTTL。全体タイムアウト予算の既定（60s、02_システム仕様.md 2.11）とStreamingの
     * 全体タイムアウト既定（300s、2.10）の双方より十分大きい値を既定とし、正常系の決済より先に
     * 誤って失効させないようにする。
     */
    val reservationTtl: Duration = Duration.ofMinutes(DEFAULT_TTL_MINUTES),
) {
    init {
        require(!reservationTtl.isNegative && !reservationTtl.isZero) {
            "reservationTtl must be positive: $reservationTtl"
        }
    }

    private companion object {
        const val DEFAULT_TTL_MINUTES = 10L
    }
}

/**
 * 03_基本設計.md 3.3.6 `QuotaManager` + 本タスクitem7（P0で保留したC2/U16の解消）。
 * 4.1 Bounded Context表でQuotaはCost Contextの所有物のためapap-costに実装する。
 *
 * `apap.domain.port.QuotaSnapshotRepository`（Routingのハードフィルタ用、読取専用の残量スナップショット）
 * とは別の、予約・決済の正合性そのものを保証する台帳である。両者の値を単一の真実源へ統合することは
 * CostEngine本実装（P7）の範囲とし、本フェーズでは決済漏れゼロの保証にスコープを絞る
 * （要件充足に影響しない実装判断のためADR化せずここに根拠を残す）。
 */
interface QuotaManager {
    /** @throws QuotaExceededException [policy]の上限（requests/tokens/cost）を超過する場合。 */
    @Suppress("LongParameterList")
    fun checkAndReserve(
        tenantId: TenantId,
        providerId: ProviderId,
        modelId: ModelId,
        estimatedTokens: TokenCount,
        estimatedCost: Money,
        policy: QuotaPolicy?,
        traceId: String,
        now: Instant,
    ): Reservation

    /** 成功時: 実Usageで確定する。 */
    fun commit(
        reservation: Reservation,
        actualUsage: Usage,
        actualCost: Money,
    )

    /** 失敗時: 予約全体を解放する。 */
    fun release(reservation: Reservation)

    /**
     * ADR-0012: Cache短絡時はrequestsカウントのみ消費（tokens/costは0）、
     * テナントスコープのみ（Providerスコープは適用しない）。予約は発行しない。
     */
    fun recordCacheShortCircuit(
        tenantId: TenantId,
        policy: QuotaPolicy?,
    )

    /**
     * 未決済のままTTLを超過した[Reservation]を解放し、失効した予約の一覧を返す
     * （呼び出し元がイベント発行/ログ記録等で検知できるようにする）。
     */
    fun expireStale(now: Instant): List<ExpiredReservation>
}
