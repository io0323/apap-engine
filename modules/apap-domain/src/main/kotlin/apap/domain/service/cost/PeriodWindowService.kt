package apap.domain.service.cost

import apap.domain.model.cost.RecurringPeriodType
import apap.domain.model.vo.Period
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * `Budget`/`QuotaPolicy`の`period`（12_ER図.md BUDGET.period / QUOTA_POLICY.period）が指す
 * 「現在の期間境界」を計算する純粋関数。`apap.cost.quota.QuotaManager`（期間別リセット）と
 * `apap.cost.CostEngine.checkBudget`（期間別Budget監視）の双方が同じ境界計算を必要とするため
 * 共有する。
 *
 * テナントのタイムゾーンは12_ER図.md/04_ドメイン設計.mdのいずれにも定義がなく、本フェーズの対象外
 * とする。UTC基準に固定する（要件充足に影響しない実装判断のためADR化せず、この判断根拠をここに残す。
 * 将来テナント別タイムゾーンが要件化された場合はこのメソッドへゾーン引数を追加する）。
 */
object PeriodWindowService {
    fun windowContaining(
        now: Instant,
        period: RecurringPeriodType,
    ): Period {
        val today = now.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS)
        val start =
            when (period) {
                RecurringPeriodType.DAILY -> today
                RecurringPeriodType.WEEKLY -> today.minusDays((today.dayOfWeek.value - 1).toLong())
                RecurringPeriodType.MONTHLY -> today.withDayOfMonth(1)
                RecurringPeriodType.YEARLY -> today.withDayOfYear(1)
            }
        val end =
            when (period) {
                RecurringPeriodType.DAILY -> start.plusDays(1)
                RecurringPeriodType.WEEKLY -> start.plusWeeks(1)
                RecurringPeriodType.MONTHLY -> start.plusMonths(1)
                RecurringPeriodType.YEARLY -> start.plusYears(1)
            }
        return Period(start.toInstant(), end.toInstant())
    }
}
