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

/** [RateLimiter.acquire]/[RateLimiter.tryAcquire]が許可した消費を表す不透明なトークン。 */
class Permit internal constructor(
    val scope: RateLimitScope,
)

/**
 * [RateLimiter.acquire]の結果。02_システム仕様.md 2.19が定義するメトリクスラベル
 * `action(wait/reject)`を、ログ文字列から再構成するのではなく戻り値として型で表現する
 * （P8のメトリクス実装がこの型を直接読み、配線をやり直さずに済むようにするため）。
 * 「待たずに即座に確保できた」場合も[Acquired]で表し、[Acquired.waitedMillis]が0になる。
 */
sealed interface AcquireResult {
    val scope: RateLimitScope

    /** トークンを確保できた（[waitedMillis]は0以上、待機なしなら0）。 */
    data class Acquired(
        override val scope: RateLimitScope,
        val permit: Permit,
        val waitedMillis: Long,
    ) : AcquireResult

    /** [maxWaitMillis]まで待った（あるいは即座に）が、なお確保できず拒否した。 */
    data class Rejected(
        override val scope: RateLimitScope,
        val waitedMillis: Long,
        val maxWaitMillis: Long,
    ) : AcquireResult
}

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
     * [maxWait]まで待ってもトークンを確保できなければ[AcquireResult.Rejected]を返す
     * （例外は使わない。wait/rejectの結果自体が呼び出し側にとって通常の分岐であり、
     * ログ文字列からの復元を要求しない構造化データとして返すため）。
     */
    suspend fun acquire(
        scope: RateLimitScope,
        traceId: String,
        maxWait: Duration,
        cost: Int = 1,
    ): AcquireResult

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
