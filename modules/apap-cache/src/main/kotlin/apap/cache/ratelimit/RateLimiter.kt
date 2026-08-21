package apap.cache.ratelimit

import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId

/**
 * 02_システム仕様.md 2.3 / ADR-0001（"apap-cacheのRate Limiterカウンタ実装"）: Token Bucketの適用対象。
 * テナント別スコープ・Provider別スコープの2段（06_システム仕様.md 2.13周辺の共有ストア方針と同じく、
 * カウンタはRDBMSに置かない）。
 */
sealed interface RateLimitScope {
    data class TenantScope(
        val tenantId: TenantId,
    ) : RateLimitScope

    data class ProviderScope(
        val providerId: ProviderId,
    ) : RateLimitScope
}

/** [RateLimiter.acquire]が拒否した際に送出する。 */
class RateLimitExceededException(
    val scope: RateLimitScope,
) : Exception("Rate limit exceeded for scope: $scope")

/** [RateLimiter.acquire]/[RateLimiter.tryAcquire]が許可した消費を表す不透明なトークン。 */
class Permit internal constructor(
    val scope: RateLimitScope,
)

/**
 * 03_基本設計.md 3.3.6 `RateLimiter`。`cost`は消費するリクエスト数単位（既定1）。
 *
 * 設計書の`acquire`は「待機 or RateLimitExceededException」と規定するが、実行予算
 * （02_システム仕様.md 2.11のタイムアウト予算管理）を不確定な待機時間で消費させないため、
 * 本実装は待機せず即時可否判定とする（FR-EXE-003は「Provider制限の遵守+テナント別流量制御、
 * Token Bucket方式」を要求するのみで待機/即時拒否の別を指定しないため、要件充足に影響しない
 * 実装判断としてADR化せずここに根拠を残す）。
 */
interface RateLimiter {
    /** @throws RateLimitExceededException 消費可能なトークンが不足している場合。 */
    fun acquire(
        scope: RateLimitScope,
        traceId: String,
        cost: Int = 1,
    ): Permit

    fun tryAcquire(
        scope: RateLimitScope,
        cost: Int = 1,
    ): Boolean

    /** [scope]専用のバケット容量・補充レートを設定する（未設定スコープは既定値を使う）。 */
    fun configure(
        scope: RateLimitScope,
        capacity: Int,
        refillPerSecond: Double,
    )
}
