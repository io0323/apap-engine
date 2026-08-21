package apap.domain.model.vo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 02_システム仕様.md 2.9: FinishReason正規化の6値。 */
class FinishReasonTest {
    @Test
    fun `has exactly the 6 values normalized by 2_9`() {
        assertEquals(
            setOf(
                FinishReason.COMPLETED,
                FinishReason.LENGTH_LIMIT,
                FinishReason.TOOL_CALL,
                FinishReason.CONTENT_FILTERED,
                FinishReason.CANCELLED,
                FinishReason.ERROR,
            ),
            FinishReason.entries.toSet(),
        )
    }
}
