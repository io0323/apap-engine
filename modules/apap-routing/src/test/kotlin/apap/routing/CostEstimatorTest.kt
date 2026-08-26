package apap.routing

import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ZeroCostEstimatorはS_costを常に定数化するスタブである。この性質を静かに埋もれさせないため、
 * [CostEstimator.isStub]で自己申告することを検証する（RoutingEngineTest側でこのフラグが
 * RoutingDecisionへ構造的に伝播することも検証している）。
 */
class CostEstimatorTest {
    @Test
    fun `ZeroCostEstimator self-reports as a stub`() {
        val estimator = ZeroCostEstimator()

        assertTrue(estimator.isStub)
    }

    @Test
    fun `ZeroCostEstimator returns zero for any provider and model`() {
        val estimator = ZeroCostEstimator(currency = "JPY")

        val cost = estimator.estimate(ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FAX"), ModelId("01ARZ3NDEKTSV4RRFFQ69G5FAY"))

        assertEquals(0, cost?.amount?.signum())
        assertEquals("JPY", cost?.currency)
    }
}
