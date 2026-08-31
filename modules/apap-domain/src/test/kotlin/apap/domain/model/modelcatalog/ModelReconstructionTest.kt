package apap.domain.model.modelcatalog

import apap.domain.event.EventMetadata
import apap.domain.event.ModelRegistered
import apap.domain.event.ModelStatusChanged
import apap.domain.event.PolicyUpdated
import apap.domain.model.UnexpectedEventForAggregateException
import apap.domain.model.reconstruct
import apap.domain.model.routing.PolicyStatus
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

/** ADR-0026: イベント列からのModel再構築が、コマンド適用後の状態と一致することを検証する。 */
class ModelReconstructionTest {
    private val modelId = ModelId(testUlid('C'))
    private val providerId = ProviderId(testUlid('A'))
    private val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))

    private fun meta(version: Long) =
        EventMetadata(
            eventId = "evt-$version",
            occurredAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(version),
            traceId = "trace-1",
            tenantId = null,
            aggregateId = modelId.value,
            version = version,
        )

    private fun registered() =
        ModelRegistered(
            meta(1),
            modelId,
            providerId,
            listOf(ModelCapability(CapabilityId("chat"), mapOf("maxTokens" to "4096"))),
            "test-model",
            "1.0",
            8000,
            2000,
            setOf(region),
            50,
        )

    @Test
    fun `reconstruction from the full event list matches the state a live command flow would reach`() {
        val statusChanged = ModelStatusChanged(meta(2), modelId, ModelStatus.REGISTERED, ModelStatus.TESTING)
        val events = listOf(registered(), statusChanged)

        val result = reconstruct(events, null, ::applyModelEvent)

        assertEquals(ModelStatus.TESTING, result?.status)
        assertEquals(listOf(ModelCapability(CapabilityId("chat"), mapOf("maxTokens" to "4096"))), result?.capabilities)
    }

    @Test
    fun `every status transition (including RETIRED) is fully captured by ModelStatusChanged alone`() {
        val toActive = ModelStatusChanged(meta(2), modelId, ModelStatus.REGISTERED, ModelStatus.ACTIVE)
        val toDeprecated = ModelStatusChanged(meta(3), modelId, ModelStatus.ACTIVE, ModelStatus.DEPRECATED)
        val toRetired = ModelStatusChanged(meta(4), modelId, ModelStatus.DEPRECATED, ModelStatus.RETIRED)
        val events = listOf(registered(), toActive, toDeprecated, toRetired)

        val result = reconstruct(events, null, ::applyModelEvent)

        assertEquals(ModelStatus.RETIRED, result?.status)
    }

    @Test
    fun `reconstructing from a snapshot plus only the events since it matches full replay`() {
        val events =
            listOf(
                registered(),
                ModelStatusChanged(meta(2), modelId, ModelStatus.REGISTERED, ModelStatus.TESTING),
                ModelStatusChanged(meta(3), modelId, ModelStatus.TESTING, ModelStatus.ACTIVE),
            )
        val fullReplay = reconstruct(events, null, ::applyModelEvent)

        val snapshotState = reconstruct(events.take(1), null, ::applyModelEvent)
        val fromSnapshot = reconstruct(events.drop(1), snapshotState, ::applyModelEvent)

        assertEquals(fullReplay, fromSnapshot)
    }

    @Test
    fun `an event that does not belong to Model throws instead of silently ignoring it`() {
        val unrelated = PolicyUpdated(meta(9), "policy-1", "PLATFORM", null, null, emptyList(), 1, PolicyStatus.ACTIVE)
        assertThrows(UnexpectedEventForAggregateException::class.java) {
            applyModelEvent(reconstruct(listOf(registered()), null, ::applyModelEvent), unrelated)
        }
    }
}
