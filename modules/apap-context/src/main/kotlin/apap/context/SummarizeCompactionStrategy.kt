package apap.context

import apap.domain.model.conversation.Turn
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.TokenCount
import org.slf4j.LoggerFactory

/**
 * 16_拡張ポイント.md 16.7「圧縮戦略」②要約圧縮: 古い区間をModelで要約し1 Turn化する。
 * 要約自体はModel呼出（I/O）を要するため、apap-contextの依存方向（apap-domainのみ）の外側の
 * 責務としてPortに切り出す。実装（実Model呼出）は本フェーズでは未接続。
 */
fun interface SummarizationPort {
    /** [turns]（切り詰め対象の古い区間）を要約した1件の[Turn]を返す。 */
    fun summarize(turns: List<Turn>): Turn
}

/**
 * [summarizer]がModel呼出を要する未接続の依存であるため、明示的opt-in + WARNログ
 * （`apap.context.NoOpQueryEmbedder`と同じスタブ規約）を要求する。予算内に収まる新しいTurnは
 * そのまま残し、収まらない古い区間を[summarizer]で1 Turnに要約して先頭に加える。
 */
class SummarizeCompactionStrategy(
    private val summarizer: SummarizationPort,
    optedIn: Boolean,
) : CompactionStrategy {
    init {
        require(optedIn) {
            "SummarizeCompactionStrategy requires explicit opt-in (optedIn=true). " +
                "Its SummarizationPort requires a Model call that is not connected this phase; " +
                "wiring this without acknowledging the gap is not allowed."
        }
        logger.warn(
            "SummarizeCompactionStrategy is wired in — verify the supplied SummarizationPort is a " +
                "real, connected implementation; this phase ships no default one.",
        )
    }

    override fun compact(
        turns: List<Turn>,
        budgetTokens: Int,
        tokensOf: (List<ContentPart>) -> TokenCount,
    ): CompactionResult {
        val keptNewestFirst = mutableListOf<Turn>()
        var remaining = budgetTokens
        var splitIndex = turns.size
        for ((index, turn) in turns.withIndex().reversed()) {
            val turnTokens = tokensOf(turn.contentParts).value
            if (turnTokens > remaining) {
                splitIndex = index + 1
                break
            }
            keptNewestFirst.add(turn)
            remaining -= turnTokens
            splitIndex = index
        }
        val kept = keptNewestFirst.reversed()
        val toSummarize = turns.subList(0, splitIndex)
        if (toSummarize.isEmpty()) {
            return CompactionResult(turns = kept, truncated = false)
        }

        val summaryTurn = summarizer.summarize(toSummarize)
        val summaryTokens = tokensOf(summaryTurn.contentParts).value
        return if (summaryTokens <= remaining) {
            CompactionResult(turns = listOf(summaryTurn) + kept, truncated = true)
        } else {
            // The summary itself doesn't fit either; fall back to dropping the summarized region entirely.
            CompactionResult(turns = kept, truncated = true)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SummarizeCompactionStrategy::class.java)
    }
}
