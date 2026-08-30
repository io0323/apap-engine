package apap.infrastructure.persistence.inmemory

import apap.domain.model.prompt.PromptTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InMemoryPromptTemplateRepositoryTest {
    private fun template(
        id: String,
        name: String,
    ) = PromptTemplate(templateId = id, name = name, body = "hello {{name}}")

    @Test
    fun `saves finds and deletes a template`() {
        val repo = InMemoryPromptTemplateRepository()
        repo.save(template("t1", "greeting"))

        assertEquals("greeting", repo.findById("t1")?.name)
        repo.delete("t1")
        assertNull(repo.findById("t1"))
    }

    @Test
    fun `findByName returns every template sharing that name`() {
        val repo = InMemoryPromptTemplateRepository()
        repo.save(template("t1", "greeting"))
        repo.save(template("t2", "greeting"))
        repo.save(template("t3", "farewell"))

        assertEquals(setOf("t1", "t2"), repo.findByName("greeting").map { it.templateId }.toSet())
    }
}
