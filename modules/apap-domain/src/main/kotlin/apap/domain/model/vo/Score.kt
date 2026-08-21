package apap.domain.model.vo

/** 04_ドメイン設計.md 4.4: 0.0–1.0。範囲外は生成時例外。 */
data class Score(
    val value: Double,
) : Comparable<Score> {
    init {
        require(value in 0.0..1.0) { "Score must be within 0.0..1.0: $value" }
    }

    /**
     * 02_システム仕様.md 2.5.2 Sticky補正(+0.05)等、既存スコアへ加減算した結果を
     * 有効範囲へ丸めて新しいScoreを得る（範囲外を理由に例外化しない、意図された調整用）。
     */
    fun coercedPlus(delta: Double): Score = Score((value + delta).coerceIn(0.0, 1.0))

    override fun compareTo(other: Score): Int = value.compareTo(other.value)

    companion object {
        val ZERO = Score(0.0)
    }
}
