package apap.domain.service.execution

import apap.domain.model.vo.ModelId
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.math.ceil

class TokenEstimationServiceTest {
    private val modelId = ModelId(testUlid('A'))

    @Test
    fun `HEURISTIC mode applies a 15 percent margin by default and always rounds up`() {
        val config = TokenEstimationConfig(charsPerTokenByModel = mapOf(modelId to 4.0))
        // 10 chars / 4.0 = 2.5 -> ceil -> 3 raw tokens; 3 * 1.15 = 3.45 -> ceil -> 4
        val estimated = TokenEstimationService.estimateHeuristic("0123456789", modelId, config)
        assertEquals(4, estimated.value)
    }

    @Test
    fun `HEURISTIC mode falls back to defaultCharsPerToken when the model is not configured`() {
        val config = TokenEstimationConfig(defaultCharsPerToken = 5.0)
        val estimated = TokenEstimationService.estimateHeuristic("a".repeat(10), ModelId(testUlid('Z')), config)
        val rawTokens = ceil(10 / 5.0)
        val expected = ceil(rawTokens * 1.15).toInt()
        assertEquals(expected, estimated.value)
    }

    @Test
    fun `EXACT mode applies a 5 percent margin by default and always rounds up`() {
        val config = TokenEstimationConfig()
        // 100 * 1.05 = 105.0 -> ceil -> 105
        assertEquals(105, TokenEstimationService.estimateExact(100, config).value)
        // 101 * 1.05 = 106.05 -> ceil -> 107
        assertEquals(107, TokenEstimationService.estimateExact(101, config).value)
    }

    @Test
    fun `estimateExact rejects a negative raw token count`() {
        assertThrows(IllegalArgumentException::class.java) {
            TokenEstimationService.estimateExact(-1, TokenEstimationConfig())
        }
    }

    @Test
    fun `margins are configurable per mode`() {
        val config = TokenEstimationConfig(exactSafetyMarginRatio = 0.10, heuristicSafetyMarginRatio = 0.20)
        assertEquals(0.10, TokenEstimationService.safetyMarginFor(TokenEstimationMode.EXACT, config), 1e-9)
        assertEquals(0.20, TokenEstimationService.safetyMarginFor(TokenEstimationMode.HEURISTIC, config), 1e-9)
    }

    @Test
    fun `TokenEstimationConfig rejects negative margins or non positive charsPerToken ratios`() {
        assertThrows(IllegalArgumentException::class.java) { TokenEstimationConfig(exactSafetyMarginRatio = -0.01) }
        assertThrows(IllegalArgumentException::class.java) {
            TokenEstimationConfig(heuristicSafetyMarginRatio = -0.01)
        }
        assertThrows(IllegalArgumentException::class.java) { TokenEstimationConfig(defaultCharsPerToken = 0.0) }
        assertThrows(IllegalArgumentException::class.java) {
            TokenEstimationConfig(charsPerTokenByModel = mapOf(modelId to 0.0))
        }
    }
}
