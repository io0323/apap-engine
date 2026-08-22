package apap.prompt

import apap.domain.model.prompt.PromptTemplateNotDraftException
import apap.domain.model.prompt.PromptTemplateNotPublishedException
import apap.domain.model.prompt.PromptTemplateStatus
import apap.testkit.inmemory.InMemoryIdGenerator
import apap.testkit.inmemory.InMemoryPromptTemplateRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PromptTemplateManagerTest {
    private val repository = InMemoryPromptTemplateRepository()
    private val manager = PromptTemplateManager(repository, InMemoryIdGenerator())

    @Test
    fun `create-publish-newRevision-archive round trip`() {
        val created = manager.create("greeting", "Hello {{name}}")
        assertEquals(PromptTemplateStatus.DRAFT, created.status)
        assertEquals(1, created.version)

        val published = manager.publish(created.templateId)
        assertEquals(PromptTemplateStatus.PUBLISHED, published.status)
        assertEquals(created.templateId, manager.findPublished("greeting")?.templateId)

        val revised = manager.newRevision(created.templateId, "Hi {{name}}!")
        assertEquals(PromptTemplateStatus.DRAFT, revised.status)
        assertEquals(2, revised.version)
        // The DRAFT revision replaces the queryable state, so it's no longer PUBLISHED-visible.
        assertNull(manager.findPublished("greeting"))

        val republished = manager.publish(revised.templateId)
        assertEquals(revised.templateId, republished.templateId)
        assertEquals("Hi {{name}}!", manager.findPublished("greeting")?.body)

        val archived = manager.archive(republished.templateId)
        assertEquals(PromptTemplateStatus.ARCHIVED, archived.status)
        assertNull(manager.findPublished("greeting"))
    }

    @Test
    fun `newRevision on a non-PUBLISHED template throws`() {
        val created = manager.create("greeting", "Hello")
        assertThrows(PromptTemplateNotPublishedException::class.java) {
            manager.newRevision(created.templateId, "Hello again")
        }
    }

    @Test
    fun `publish on an already PUBLISHED template throws`() {
        val created = manager.create("greeting", "Hello")
        manager.publish(created.templateId)
        assertThrows(PromptTemplateNotDraftException::class.java) {
            manager.publish(created.templateId)
        }
    }

    @Test
    fun `get throws for an unknown templateId`() {
        assertThrows(PromptTemplateNotFoundException::class.java) { manager.get("does-not-exist") }
    }

    @Test
    fun `findPublished returns null when no PUBLISHED revision exists`() {
        manager.create("greeting", "Hello")
        assertNull(manager.findPublished("greeting"))
    }
}
