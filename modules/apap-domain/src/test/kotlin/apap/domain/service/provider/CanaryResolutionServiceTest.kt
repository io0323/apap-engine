package apap.domain.service.provider

import apap.domain.model.modelcatalog.AliasTarget
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.abs

class CanaryResolutionServiceTest {
    private val modelA = ModelId(testUlid('A'))
    private val modelB = ModelId(testUlid('B'))
    private val targets = listOf(AliasTarget(modelA, 90), AliasTarget(modelB, 10))

    private fun randomRequestId(): RequestId {
        // ULID形式の要件は満たさないため、テスト専用の26文字Crockford風文字列を生成する
        val raw =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .uppercase()
                .take(26)
                .padEnd(26, '0')
        return RequestId(raw.map { if (it in "ILOU") '2' else it }.joinToString(""))
    }

    @Test
    fun `same requestId always resolves to the same model`() {
        val requestId = randomRequestId()
        val first = CanaryResolutionService.resolve(targets, requestId)
        val second = CanaryResolutionService.resolve(targets, requestId)
        assertEquals(first, second)
    }

    @Test
    fun `only ever resolves to one of the declared targets`() {
        repeat(200) {
            val resolved = CanaryResolutionService.resolve(targets, randomRequestId())
            assert(resolved == modelA || resolved == modelB)
        }
    }

    @Test
    fun `distribution roughly matches the declared weights`() {
        val sampleSize = 20_000
        val countA = (1..sampleSize).count { CanaryResolutionService.resolve(targets, randomRequestId()) == modelA }
        val observedRatio = countA.toDouble() / sampleSize
        // 90%が期待値。統計的揺らぎを許容し、5ポイント以内の誤差を許容する。
        assert(abs(observedRatio - 0.90) < 0.05) { "observed ratio for modelA was $observedRatio" }
    }

    @Test
    fun `rejects an empty target list`() {
        val requestId = randomRequestId()
        assertThrows(IllegalArgumentException::class.java) { CanaryResolutionService.resolve(emptyList(), requestId) }
    }

    @Test
    fun `rejects targets whose total weight is zero`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanaryResolutionService.resolve(listOf(AliasTarget(modelA, 0)), randomRequestId())
        }
    }
}
