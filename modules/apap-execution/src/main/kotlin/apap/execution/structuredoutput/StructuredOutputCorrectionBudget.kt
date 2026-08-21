package apap.execution.structuredoutput

import java.util.concurrent.atomic.AtomicInteger

/** ADR-0011 決定2。CLAUDE.md不変条件7に従い設定可能、既定値はADRと一致させる。 */
data class StructuredOutputConfig(
    val maxCorrectionsPerRequest: Int = 2,
) {
    init {
        require(maxCorrectionsPerRequest >= 0) {
            "maxCorrectionsPerRequest must not be negative: $maxCorrectionsPerRequest"
        }
    }
}

/**
 * ADR-0011 決定4: 「是正回数はリクエスト全体で最大2回とし、Fallbackで別候補へ移ってもリセットしない」。
 * `requestId`単位でグローバルに1個生成し、[apap.execution.attempt.AttemptExecutor]の全呼出
 * （Fallbackで候補が変わっても同一インスタンス）へ引き回す。ADR-0011の警告
 * （「リセットを許すとFallback3段×是正2回=最大9回のProvider呼出になる」）を防ぐための唯一の防波堤。
 */
class StructuredOutputCorrectionBudget(
    config: StructuredOutputConfig = StructuredOutputConfig(),
) {
    private val remaining = AtomicInteger(config.maxCorrectionsPerRequest)

    /** @return 是正枠を1つ消費できた場合true。枠が尽きていればfalse（状態は変更しない）。 */
    fun tryConsume(): Boolean {
        while (true) {
            val current = remaining.get()
            if (current <= 0) return false
            if (remaining.compareAndSet(current, current - 1)) return true
        }
    }
}
