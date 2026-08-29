package apap.observability.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HealthCheckServiceTest {
    @Test
    fun `liveness is always UP`() {
        val service = HealthCheckService()
        assertEquals(HealthState.UP, service.liveness().state)
    }

    @Test
    fun `readiness is UP when there are no indicators`() {
        val service = HealthCheckService(readinessIndicators = emptyList())
        assertEquals(HealthState.UP, service.readiness().state)
    }

    @Test
    fun `readiness reflects the worst indicator state`() {
        val up = HealthIndicator { HealthCheckResult(HealthState.UP) }
        val degraded = HealthIndicator { HealthCheckResult(HealthState.DEGRADED, mapOf("cache" to "slow")) }
        val down = HealthIndicator { HealthCheckResult(HealthState.DOWN, mapOf("db" to "unreachable")) }

        val service = HealthCheckService(readinessIndicators = listOf(up, degraded))
        assertEquals(HealthState.DEGRADED, service.readiness().state)

        val serviceWithDown = HealthCheckService(readinessIndicators = listOf(up, degraded, down))
        val result = serviceWithDown.readiness()
        assertEquals(HealthState.DOWN, result.state)
        assertEquals("unreachable", result.details["db"])
        assertEquals("slow", result.details["cache"])
    }

    @Test
    fun `providerHealth delegates to the injected indicator`() {
        val indicator = HealthIndicator { HealthCheckResult(HealthState.DOWN, mapOf("provider-1" to "DOWN")) }
        val service = HealthCheckService(providerHealthIndicator = indicator)
        val result = service.providerHealth()
        assertEquals(HealthState.DOWN, result.state)
        assertEquals("DOWN", result.details["provider-1"])
    }
}
