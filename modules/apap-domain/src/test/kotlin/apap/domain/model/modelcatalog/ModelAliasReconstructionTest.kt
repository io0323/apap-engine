package apap.domain.model.modelcatalog

import apap.domain.event.AliasChanged
import apap.domain.event.AliasTargetSnapshot
import apap.domain.event.BatchJobCancelled
import apap.domain.event.EventMetadata
import apap.domain.model.UnexpectedEventForAggregateException
import apap.domain.model.reconstruct
import apap.domain.model.vo.AliasId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

/** ADR-0026: イベント列からのModelAlias再構築が、コマンド適用後の状態と一致することを検証する。 */
class ModelAliasReconstructionTest {
    private val aliasId = AliasId(testUlid('D'))
    private val modelA = ModelId(testUlid('C'))
    private val modelB = ModelId(testUlid('E'))

    private fun meta(version: Long) =
        EventMetadata(
            eventId = "evt-$version",
            occurredAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(version),
            traceId = "trace-1",
            tenantId = null,
            aggregateId = aliasId.value,
            version = version,
        )

    @Test
    fun `reconstruction from the full event list matches the state a live command flow would reach`() {
        val created =
            AliasChanged(meta(1), aliasId.value, "chat-standard", emptyList(), listOf(AliasTargetSnapshot(modelA, 100)))
        val canaryShift =
            AliasChanged(
                meta(2),
                aliasId.value,
                "chat-standard",
                listOf(AliasTargetSnapshot(modelA, 100)),
                listOf(AliasTargetSnapshot(modelA, 90), AliasTargetSnapshot(modelB, 10)),
            )

        val result = reconstruct(listOf(created, canaryShift), null, ::applyModelAliasEvent)

        assertEquals(
            ModelAlias(aliasId, "chat-standard", listOf(AliasTarget(modelA, 90), AliasTarget(modelB, 10))),
            result,
        )
    }

    @Test
    fun `reconstructing from a snapshot plus only the events since it matches full replay`() {
        val created =
            AliasChanged(meta(1), aliasId.value, "chat-standard", emptyList(), listOf(AliasTargetSnapshot(modelA, 100)))
        val canaryShift =
            AliasChanged(
                meta(2),
                aliasId.value,
                "chat-standard",
                listOf(AliasTargetSnapshot(modelA, 100)),
                listOf(AliasTargetSnapshot(modelA, 90), AliasTargetSnapshot(modelB, 10)),
            )
        val events = listOf(created, canaryShift)
        val fullReplay = reconstruct(events, null, ::applyModelAliasEvent)

        val snapshotState = reconstruct(events.take(1), null, ::applyModelAliasEvent)
        val fromSnapshot = reconstruct(events.drop(1), snapshotState, ::applyModelAliasEvent)

        assertEquals(fullReplay, fromSnapshot)
    }

    @Test
    fun `an event that does not belong to ModelAlias throws instead of silently ignoring it`() {
        assertThrows(UnexpectedEventForAggregateException::class.java) {
            applyModelAliasEvent(null, BatchJobCancelled(meta(9), "job-1"))
        }
    }
}
