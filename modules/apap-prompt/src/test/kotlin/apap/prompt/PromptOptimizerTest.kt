package apap.prompt

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.RequestId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PromptOptimizerTest {
    private val requestId = RequestId("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val capabilityId = CapabilityId("chat")

    private fun draft(
        text: String,
        templateVariables: Map<String, String> = emptyMap(),
    ) = PromptDraft(requestId, capabilityId, listOf(ContentPart.Text(text)), templateVariables = templateVariables)

    @Test
    fun `collapses redundant whitespace`() {
        val result = PromptOptimizer().optimize(draft("hello    there\n\n  world"))
        assertEquals("hello there world", (result.input.single() as ContentPart.Text).text)
    }

    @Test
    fun `does not collapse whitespace when disabled`() {
        val optimizer = PromptOptimizer(PromptOptimizationConfig(collapseWhitespace = false))
        val result = optimizer.optimize(draft("hello    there"))
        assertEquals("hello    there", (result.input.single() as ContentPart.Text).text)
    }

    @Test
    fun `resolves template variables from the draft`() {
        val result = PromptOptimizer().optimize(draft("hi {{name}}", templateVariables = mapOf("name" to "Alice")))
        assertEquals("hi Alice", (result.input.single() as ContentPart.Text).text)
    }

    @Test
    fun `falls back to static variables when the draft does not supply one`() {
        val optimizer = PromptOptimizer(PromptOptimizationConfig(staticVariables = mapOf("name" to "Default")))
        val result = optimizer.optimize(draft("hi {{name}}"))
        assertEquals("hi Default", (result.input.single() as ContentPart.Text).text)
    }

    @Test
    fun `draft-supplied variables override static defaults`() {
        val optimizer = PromptOptimizer(PromptOptimizationConfig(staticVariables = mapOf("name" to "Default")))
        val result = optimizer.optimize(draft("hi {{name}}", templateVariables = mapOf("name" to "Alice")))
        assertEquals("hi Alice", (result.input.single() as ContentPart.Text).text)
    }

    @Test
    fun `leaves unresolved placeholders untouched`() {
        val result = PromptOptimizer().optimize(draft("hi {{unknown}}"))
        assertEquals("hi {{unknown}}", (result.input.single() as ContentPart.Text).text)
    }
}
