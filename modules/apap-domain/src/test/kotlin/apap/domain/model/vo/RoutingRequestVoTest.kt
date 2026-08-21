package apap.domain.model.vo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class RoutingRequestVoTest {
    @Test
    fun `RoutingConstraints rejects non positive maxLatencyMs`() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutingConstraints(maxLatencyMs = 0)
        }
    }

    @Test
    fun `RoutingConstraints defaults to no restriction`() {
        val constraints = RoutingConstraints()
        assertEquals(null, constraints.region)
        assertEquals(null, constraints.maxCost)
        assertEquals(emptySet<ProviderId>(), constraints.excludeProviders)
    }

    @Test
    fun `RoutingConstraints accepts a positive maxLatencyMs and maxCost`() {
        RoutingConstraints(maxLatencyMs = 500, maxCost = Money(BigDecimal.ONE, "USD"))
    }

    @Test
    fun `RoutingPreferences defaults to balanced`() {
        assertEquals(OptimizeFor.BALANCED, RoutingPreferences().optimizeFor)
    }
}
