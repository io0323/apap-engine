package apap.cache.ratelimit

import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.TenantId
import java.time.Duration

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
 * 02_システム仕様.md 2.8 step8bは「待機 or 即時」、2.19のメトリクスも
 * `action(wait/reject)`を区別する。ゼロ待機（即時可否判定のみ）はタイムアウト予算を
 * 消費しないという点では安全だが、設計が求める「待機」を実装しないことになり、
 * 瞬間的なバースト（本来は数十ms待てば通るはずのトラフィック）まで即座に拒否してしまう。
 * [acquire]は[maxWait]で上限を切った**有界待機**を行う。呼び出し側はリクエストの残
 * タイムアウト予算と設定可能な上限（[apap.cache.ratelimit.RateLimiterConfig]参照）の
 * 小さいほうを[maxWait]として渡すこと（無制限待機やゼロ固定を避けるため、既定値は
 * インターフェースに持たせず呼び出し側に選択させる）。
 */
interface RateLimiter {
    /**
     * [maxWait]まで待ってもトークンを確保できなければ拒否する。
     * @throws RateLimitExceededException 待機してもなお消費可能なトークンが不足している場合。
     */
    suspend fun acquire(
        scope: RateLimitScope,
        traceId: String,
        maxWait: Duration,
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
