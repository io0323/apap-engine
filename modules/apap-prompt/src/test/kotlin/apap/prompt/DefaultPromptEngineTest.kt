package apap.prompt

import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.RequestId
import apap.domain.model.vo.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

class DefaultPromptEngineTest {
    private fun request(text: String) =
        CanonicalRequest(
            requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
            tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FAW"),
            principal = "user-1",
            capabilityId = CapabilityId("chat"),
            input = listOf(ContentPart.Text(text)),
            timeoutBudget = Duration.ofSeconds(30),
            traceId = "trace-1",
        )

    @Test
    fun `default pipeline validates, optimizes and passes through rendering`() {
        val engine = DefaultPromptEngine()
        val result = engine.process(request("hello    world"))
        assertEquals("hello world", (result.input.single() as ContentPart.Text).text)
    }

    @Test
    fun `default pipeline rejects oversized input via the Validation stage`() {
        val validator = PromptValidator(PromptValidationConfig(maxInputChars = 3))
        val engine = DefaultPromptEngine(PromptPipeline.default(validator = validator))
        assertThrows(PromptValidationFailedException::class.java) {
            engine.process(request("far too long for the configured limit"))
        }
    }

    @Test
    fun `a custom stage inserted into the default pipeline is reflected in the output`() {
        val marker = PromptStage { draft, _ -> draft.copy(input = draft.input + ContentPart.Text("[marked]")) }
        val engine = DefaultPromptEngine(PromptPipeline.default().withStageAt(0, marker))
        val result = engine.process(request("hello"))
        assertEquals(2, result.input.size)
        assertEquals("[marked]", (result.input.last() as ContentPart.Text).text)
    }
}
