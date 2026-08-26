package apap.cache

import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.GenerationParams
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.RoutingConstraints
import apap.domain.model.vo.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** 02_システム仕様.md 2.14: キー正規化の安定性検証。 */
class NormalizedJsonCacheKeyStrategyTest {
    private val strategy = NormalizedJsonCacheKeyStrategy()
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val capabilityId = CapabilityId("embedding")

    private fun request(
        requestId: String = "01ARZ3NDEKTSV4RRFFQ69G5FA1",
        traceId: String = "trace-1",
        idempotencyKey: String? = null,
    ): CanonicalRequest =
        CanonicalRequest(
            requestId = RequestId(requestId),
            tenantId = tenantId,
            principal = "user-1",
            capabilityId = capabilityId,
            input = listOf(ContentPart.Text("hello world")),
            params = GenerationParams(temperature = 0.0),
            idempotencyKey = idempotencyKey,
            timeoutBudget = Duration.ofSeconds(30),
            traceId = traceId,
        )

    private fun keyFor(
        req: CanonicalRequest,
        resolvedAliasId: String? = null,
    ): String = strategy.responseCacheKey(tenantId, capabilityId, resolvedAliasId, req)

    @Test
    fun `same content yields the same response cache key across separate strategy instances`() {
        val keyA = keyFor(request())
        val keyB = NormalizedJsonCacheKeyStrategy().responseCacheKey(tenantId, capabilityId, null, request())
        assertEquals(keyA, keyB)
    }

    @Test
    fun `requestId and traceId differences do not change the response cache key`() {
        val keyA = keyFor(request(requestId = "01ARZ3NDEKTSV4RRFFQ69G5FA1", traceId = "trace-1"))
        val keyB = keyFor(request(requestId = "01ARZ3NDEKTSV4RRFFQ69G5FA2", traceId = "trace-2"))
        assertEquals(keyA, keyB)
    }

    @Test
    fun `excludeProviders set iteration order does not change the response cache key`() {
        val providerA = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA3")
        val providerB = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FA4")
        val orderAB = RoutingConstraints(excludeProviders = setOf(providerA, providerB))
        val orderBA = RoutingConstraints(excludeProviders = setOf(providerB, providerA))

        val keyAB = keyFor(request().copy(constraints = orderAB))
        val keyBA = keyFor(request().copy(constraints = orderBA))
        assertEquals(keyAB, keyBA)
    }

    @Test
    fun `different content yields a different response cache key`() {
        val keyA = keyFor(request())
        val keyB = keyFor(request().copy(input = listOf(ContentPart.Text("goodbye world"))))
        assertNotEquals(keyA, keyB)
    }

    @Test
    fun `resolvedAliasId changes the response cache key prefix`() {
        val keyNoAlias = keyFor(request())
        val keyWithAlias = keyFor(request(), resolvedAliasId = "01ARZ3NDEKTSV4RRFFQ69G5FA5")
        assertNotEquals(keyNoAlias, keyWithAlias)

        val expectedPrefix = ResponseCacheKeys.aliasPrefix(null)
        assertTrue(keyNoAlias.startsWith(expectedPrefix))
    }
}
