package apap.runtime

import apap.api.ApapHealth
import apap.api.ApapHealthResult
import apap.api.ApapHealthState
import apap.observability.health.HealthCheckResult
import apap.observability.health.HealthCheckService
import apap.observability.health.HealthState

internal class DefaultApapHealth(
    private val delegate: HealthCheckService,
) : ApapHealth {
    override fun liveness(): ApapHealthResult = delegate.liveness().toApi()

    override fun readiness(): ApapHealthResult = delegate.readiness().toApi()

    override fun providerHealth(): ApapHealthResult = delegate.providerHealth().toApi()

    private fun HealthCheckResult.toApi(): ApapHealthResult = ApapHealthResult(state.toApi(), details)

    private fun HealthState.toApi(): ApapHealthState =
        when (this) {
            HealthState.UP -> ApapHealthState.UP
            HealthState.DEGRADED -> ApapHealthState.DEGRADED
            HealthState.DOWN -> ApapHealthState.DOWN
        }
}
