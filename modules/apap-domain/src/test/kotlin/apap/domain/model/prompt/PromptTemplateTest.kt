package apap.domain.model.prompt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PromptTemplateTest {
    private fun template(status: PromptTemplateStatus = PromptTemplateStatus.DRAFT) =
        PromptTemplate(templateId = "t1", name = "greeting", body = "Hello {{name}}", status = status)

    @Test
    fun `rejects blank name or body`() {
        assertThrows(IllegalArgumentException::class.java) { template().copy(name = " ") }
        assertThrows(IllegalArgumentException::class.java) { template().copy(body = " ") }
    }

    @Test
    fun `rejects version below 1`() {
        assertThrows(IllegalArgumentException::class.java) { template().copy(version = 0) }
    }

    @Test
    fun `publish moves DRAFT to PUBLISHED`() {
        assertEquals(PromptTemplateStatus.PUBLISHED, template().publish().status)
    }

    @Test
    fun `publish rejects a template that is not DRAFT`() {
        assertThrows(PromptTemplateNotDraftException::class.java) {
            template(status = PromptTemplateStatus.PUBLISHED).publish()
        }
    }

    @Test
    fun `newRevision requires PUBLISHED and increments version back to DRAFT`() {
        assertThrows(PromptTemplateNotPublishedException::class.java) { template().newRevision("new body") }
        val published = template().publish()
        val revised = published.newRevision("new body")
        assertEquals(2, revised.version)
        assertEquals(PromptTemplateStatus.DRAFT, revised.status)
        assertEquals("new body", revised.body)
    }

    @Test
    fun `newRevision rejects a template that is still DRAFT`() {
        val draft = template(status = PromptTemplateStatus.DRAFT)
        assertThrows(PromptTemplateNotPublishedException::class.java) { draft.newRevision("x") }
    }

    @Test
    fun `archive changes status`() {
        assertEquals(PromptTemplateStatus.ARCHIVED, template().archive().status)
    }

    @Test
    fun `TemplateVariable rejects blank name`() {
        assertThrows(IllegalArgumentException::class.java) { TemplateVariable(" ") }
    }

    @Test
    fun `TemplateVariable accepts a non-blank name with optional required flag and default`() {
        val variable = TemplateVariable("name", required = false, defaultValue = "guest")
        assertEquals("name", variable.name)
        assertEquals("guest", variable.defaultValue)
    }
}
