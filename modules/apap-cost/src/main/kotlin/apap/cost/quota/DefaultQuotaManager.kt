package apap.cost.quota

import apap.domain.event.EventMetadata
import apap.domain.event.QuotaExceeded
import apap.domain.model.cost.QuotaPolicy
import apap.domain.model.cost.RecurringPeriodType
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.Money
import apap.domain.model.vo.Period
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import apap.domain.model.vo.TokenCount
import apap.domain.model.vo.Usage
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import apap.domain.service.cost.PeriodWindowService
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * テナント単位の予約済(pending)/確定済(committed)累計。[currentWindow]が保持する期間境界を
 * 跨いだことを検知した時点で、そのテナントの累計をゼロへ戻す（FR-EXE-004: 期間別リセット）。
 * `policy`未指定のテナントにも既定でDAILY境界を適用する（[DefaultQuotaManager.ledgerFor]参照）。
 */
private class TenantLedger {
    var currentWindow: Period? = null
    var committedRequests: Long = 0
    var committedTokens: Long = 0
    var committedCost: Money? = null
    var pendingRequests: Long = 0
    var pendingTokens: Long = 0
    var pendingCost: Money? = null

    fun resetForNewWindow(window: Period) {
        currentWindow = window
        committedRequests = 0
        committedTokens = 0
        committedCost = null
        pendingRequests = 0
        pendingTokens = 0
        pendingCost = null
    }
}

/**
 * [QuotaManager]の既定実装。決済漏れ0を保証するため、[checkAndReserve]/[commit]/[release]/
 * [expireStale]のすべてで、まず期限切れpending予約を掃除してから本処理へ進む（外部Schedulerが
 * [expireStale]を呼ばなくても、後続の呼出のたびに自己修復する）。
 *
 * 期間境界リセット（FR-EXE-004）は、新規の消費が発生する2つの入口（[checkAndReserve]・
 * [recordCacheShortCircuit]）でのみ[ledgerFor]を通じて判定する。[commit]/[release]は
 * 既存の[Reservation]（[checkAndReserve]時点で境界判定済み）を決済するだけであり、ここで
 * 改めて境界判定を行うと、境界を跨ぐ間際に作られた予約の決済分がリセットで失われかねないため
 * 意図的に対象外とする（要件充足に影響しない実装判断のためADR化せず、根拠をここに残す）。
 */
@Suppress("TooManyFunctions")
class DefaultQuotaManager(
    private val idGenerator: IdGenerator,
    private val clock: Clock,
    private val eventPublisher: DomainEventPublisher,
    private val config: QuotaManagerConfig = QuotaManagerConfig(),
) : QuotaManager {
    private val ledgers = ConcurrentHashMap<TenantId, TenantLedger>()
    private val reservations = ConcurrentHashMap<String, Reservation>()

    @Suppress("LongParameterList")
    @Synchronized
    override fun checkAndReserve(
        tenantId: TenantId,
        providerId: ProviderId,
        modelId: ModelId,
        estimatedTokens: TokenCount,
        estimatedCost: Money,
        policy: QuotaPolicy?,
        traceId: String,
        now: Instant,
    ): Reservation {
        sweepExpired(now)
        val ledger = ledgerFor(tenantId, policy, now)
        if (policy != null) {
            enforceLimits(tenantId, ledger, policy, estimatedTokens, estimatedCost, traceId)
        }
        ledger.pendingRequests += 1
        ledger.pendingTokens += estimatedTokens.value
        ledger.pendingCost = accumulate(ledger.pendingCost, estimatedCost)

        val reservation =
            Reservation(
                reservationId = idGenerator.newId(),
                tenantId = tenantId,
                providerId = providerId,
                modelId = modelId,
                estimatedTokens = estimatedTokens,
                estimatedCost = estimatedCost,
                createdAt = now,
                expiresAt = now.plus(config.reservationTtl),
            )
        reservations[reservation.reservationId] = reservation
        return reservation
    }

    @Synchronized
    override fun commit(
        reservation: Reservation,
        actualUsage: Usage,
        actualCost: Money,
    ) {
        val stored = requirePending(reservation)
        val ledger = ledgers.computeIfAbsent(stored.tenantId) { TenantLedger() }
        releasePendingAmounts(ledger, stored)
        ledger.committedRequests += 1
        ledger.committedTokens += actualUsage.totalTokens.value
        ledger.committedCost = accumulate(ledger.committedCost, actualCost)
        reservations[stored.reservationId] = stored.copy(status = ReservationStatus.COMMITTED)
    }

    @Synchronized
    override fun release(reservation: Reservation) {
        val stored = requirePending(reservation)
        val ledger = ledgers.computeIfAbsent(stored.tenantId) { TenantLedger() }
        releasePendingAmounts(ledger, stored)
        reservations[stored.reservationId] = stored.copy(status = ReservationStatus.RELEASED)
    }

    @Synchronized
    override fun recordCacheShortCircuit(
        tenantId: TenantId,
        policy: QuotaPolicy?,
    ) {
        // ADR-0012: Cache短絡時はrequestsのみ・テナントスコープのみ消費。予約フェーズを経ないため
        // pendingではなく直接committedへ計上する。
        val ledger = ledgerFor(tenantId, policy, clock.now())
        ledger.committedRequests += 1
    }

    @Synchronized
    override fun expireStale(now: Instant): List<ExpiredReservation> {
        val expired = mutableListOf<ExpiredReservation>()
        reservations.values
            .filter { it.status == ReservationStatus.PENDING && it.expiresAt.isBefore(now) }
            .forEach { stale ->
                val ledger = ledgers.computeIfAbsent(stale.tenantId) { TenantLedger() }
                releasePendingAmounts(ledger, stale)
                val expiredReservation = stale.copy(status = ReservationStatus.EXPIRED)
                reservations[stale.reservationId] = expiredReservation
                expired += ExpiredReservation(expiredReservation, now)
            }
        return expired
    }

    private fun sweepExpired(now: Instant) {
        expireStale(now)
    }

    /** FR-EXE-004: [tenantId]の現在の期間境界を計算し、前回の境界と異なれば台帳をリセットする。 */
    private fun ledgerFor(
        tenantId: TenantId,
        policy: QuotaPolicy?,
        now: Instant,
    ): TenantLedger {
        val periodType = policy?.period ?: RecurringPeriodType.DAILY
        val window = PeriodWindowService.windowContaining(now, periodType)
        val ledger = ledgers.computeIfAbsent(tenantId) { TenantLedger() }
        if (ledger.currentWindow != window) {
            ledger.resetForNewWindow(window)
        }
        return ledger
    }

    private fun requirePending(reservation: Reservation): Reservation {
        val stored =
            reservations[reservation.reservationId]
                ?: error("Unknown reservation: ${reservation.reservationId}")
        check(stored.status == ReservationStatus.PENDING) {
            "Reservation ${stored.reservationId} is already ${stored.status}, cannot settle twice"
        }
        return stored
    }

    private fun releasePendingAmounts(
        ledger: TenantLedger,
        reservation: Reservation,
    ) {
        ledger.pendingRequests -= 1
        ledger.pendingTokens -= reservation.estimatedTokens.value
        ledger.pendingCost = subtract(ledger.pendingCost, reservation.estimatedCost)
    }

    @Suppress("LongParameterList", "ThrowsCount")
    private fun enforceLimits(
        tenantId: TenantId,
        ledger: TenantLedger,
        policy: QuotaPolicy,
        estimatedTokens: TokenCount,
        estimatedCost: Money,
        traceId: String,
    ) {
        policy.limits.requests?.let { limit ->
            if (ledger.committedRequests + ledger.pendingRequests + 1 > limit) {
                throw quotaExceeded(tenantId, policy.quotaId, "requests", traceId)
            }
        }
        policy.limits.tokens?.let { limit ->
            if (ledger.committedTokens + ledger.pendingTokens + estimatedTokens.value > limit) {
                throw quotaExceeded(tenantId, policy.quotaId, "tokens", traceId)
            }
        }
        policy.limits.cost?.let { limit ->
            val committed = ledger.committedCost ?: Money.zero(limit.currency)
            val pending = ledger.pendingCost ?: Money.zero(limit.currency)
            val projected = committed + pending + estimatedCost
            if (projected > limit) {
                throw quotaExceeded(tenantId, policy.quotaId, "cost", traceId)
            }
        }
    }

    private fun quotaExceeded(
        tenantId: TenantId,
        quotaId: String,
        dimension: String,
        traceId: String,
    ): QuotaExceededException {
        eventPublisher.publish(
            QuotaExceeded(
                meta =
                    EventMetadata(
                        eventId = idGenerator.newId(),
                        occurredAt = clock.now(),
                        traceId = traceId,
                        tenantId = tenantId,
                        aggregateId = tenantId.value,
                        version = 0,
                    ),
                tenantId = tenantId,
                quotaId = quotaId,
                dimension = dimension,
            ),
        )
        return QuotaExceededException(tenantId, quotaId, dimension)
    }

    private fun accumulate(
        current: Money?,
        delta: Money,
    ): Money = (current ?: Money.zero(delta.currency)) + delta

    private fun subtract(
        current: Money?,
        delta: Money,
    ): Money? = current?.let { it - delta }
}
