package apap.adapter.spi

import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

class SpiValueObjectsTest {
    private fun request(
        modelName: String = "model-a",
        timeout: Duration = Duration.ofSeconds(30),
    ) = AdapterRequest(
        capabilityId = CapabilityId("chat"),
        modelName = modelName,
        input = emptyList(),
        timeout = timeout,
        authContext = AuthContext(),
    )

    @Test
    fun `AdapterRequest rejects a blank modelName`() {
        assertThrows(IllegalArgumentException::class.java) { request(modelName = " ") }
    }

    @Test
    fun `AdapterRequest rejects a non positive timeout`() {
        assertThrows(IllegalArgumentException::class.java) { request(timeout = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { request(timeout = Duration.ofSeconds(-1)) }
    }

    @Test
    fun `AdapterChunk rejects a negative index`() {
        assertThrows(IllegalArgumentException::class.java) {
            AdapterChunk(type = AdapterChunkType.CONTENT_DELTA, index = -1)
        }
    }

    @Test
    fun `CapabilityConstraints rejects non positive token limits`() {
        assertThrows(IllegalArgumentException::class.java) { CapabilityConstraints(maxInputTokens = 0) }
        assertThrows(IllegalArgumentException::class.java) { CapabilityConstraints(maxOutputTokens = 0) }
    }

    @Test
    fun `DiscoveredModel rejects blank names and non positive limits`() {
        assertThrows(IllegalArgumentException::class.java) { discoveredModel(modelName = " ") }
        assertThrows(IllegalArgumentException::class.java) { discoveredModel(version = " ") }
        assertThrows(IllegalArgumentException::class.java) { discoveredModel(contextWindow = 0) }
        assertThrows(IllegalArgumentException::class.java) { discoveredModel(maxOutputTokens = 0) }
    }

    private fun discoveredModel(
        modelName: String = "model-a",
        version: String = "v1",
        contextWindow: Int = 8000,
        maxOutputTokens: Int = 1000,
    ) = DiscoveredModel(
        modelName = modelName,
        version = version,
        capabilities = setOf(CapabilityId("chat")),
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        regions = setOf("jp-east"),
    )

    @Test
    fun `HealthResult rejects a negative latency`() {
        assertThrows(IllegalArgumentException::class.java) {
            HealthResult(status = ProviderHealthStatus.UP, latency = Duration.ofSeconds(-1))
        }
    }

    @Test
    fun `AdapterConfig rejects an empty endpoints list`() {
        val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
        assertThrows(IllegalArgumentException::class.java) {
            AdapterConfig(
                providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                endpoints = emptyList(),
                rateLimits = RateLimits(rpm = 60, tpm = 100_000, concurrent = 10),
                regions = setOf(region),
            )
        }
        val ok =
            AdapterConfig(
                providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                endpoints = listOf(Endpoint("ep1", region, "https://example.internal", 100)),
                rateLimits = RateLimits(rpm = 60, tpm = 100_000, concurrent = 10),
                regions = setOf(region),
            )
        assert(ok.endpoints.size == 1)
    }
}
