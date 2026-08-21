package apap.testkit.inmemory

import apap.domain.port.Clock
import java.time.Instant

/**
 * CLAUDE.md実装規約: 時刻はPort化して注入する。UseCaseテストの決定性のため、
 * `now`を明示的に進められる[Clock]実装を提供する。
 */
class InMemoryClock(
    private var current: Instant = Instant.parse("2026-01-01T00:00:00Z"),
) : Clock {
    override fun now(): Instant = current

    fun advanceTo(instant: Instant) {
        current = instant
    }

    fun advanceBy(seconds: Long) {
        current = current.plusSeconds(seconds)
    }
}
