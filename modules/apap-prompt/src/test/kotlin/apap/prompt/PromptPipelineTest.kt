package apap.prompt

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 16_拡張ポイント.md 16.7: PromptStageを任意位置へ挿入できること。 */
class PromptPipelineTest {
    private val requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FAW")
    private val ctx = PromptStageContext(tenantId, "trace-1")

    private fun draft() = PromptDraft(requestId, CapabilityId("chat"), listOf(ContentPart.Text("hello")))

    @Test
    fun `stages run in list order`() {
        val order = mutableListOf<String>()
        val pipeline =
            PromptPipeline(
                listOf(
                    PromptStage { d, _ ->
                        order.add("first")
                        d
                    },
                    PromptStage { d, _ ->
                        order.add("second")
                        d
                    },
                    PromptStage { d, _ ->
                        order.add("third")
                        d
                    },
                ),
            )
        pipeline.run(draft(), ctx)
        assertEquals(listOf("first", "second", "third"), order)
    }

    @Test
    fun `inserting a stage at an arbitrary index changes processing order`() {
        val order = mutableListOf<String>()
        val base =
            PromptPipeline(
                listOf(
                    PromptStage { d, _ ->
                        order.add("first")
                        d
                    },
                    PromptStage { d, _ ->
                        order.add("third")
                        d
                    },
                ),
            )
        val withInserted =
            base.withStageAt(
                1,
                PromptStage { d, _ ->
                    order.add("second")
                    d
                },
            )

        withInserted.run(draft(), ctx)

        assertEquals(listOf("first", "second", "third"), order)
    }

    @Test
    fun `withStageAt does not mutate the original pipeline`() {
        val order = mutableListOf<String>()
        val base =
            PromptPipeline(
                listOf(
                    PromptStage { d, _ ->
                        order.add("only")
                        d
                    },
                ),
            )
        base.withStageAt(
            0,
            PromptStage { d, _ ->
                order.add("inserted")
                d
            },
        )

        base.run(draft(), ctx)

        assertEquals(listOf("only"), order)
    }
}
