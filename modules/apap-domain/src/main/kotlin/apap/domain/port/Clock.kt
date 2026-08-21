package apap.domain.port

import java.time.Instant

/**
 * CLAUDE.md 実装規約: 時刻・IDはPort化して注入する（テストの決定性のため
 * `System.currentTimeMillis()`の直接呼び出しをdomain内で禁止）。
 */
interface Clock {
    fun now(): Instant
}
