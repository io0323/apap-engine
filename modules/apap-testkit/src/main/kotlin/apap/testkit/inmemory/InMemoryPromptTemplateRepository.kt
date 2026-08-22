package apap.testkit.inmemory

import apap.domain.model.prompt.PromptTemplate
import apap.domain.port.PromptTemplateRepository

class InMemoryPromptTemplateRepository : PromptTemplateRepository {
    private val templates = mutableMapOf<String, PromptTemplate>()

    override fun findById(templateId: String): PromptTemplate? = templates[templateId]

    override fun findByName(name: String): List<PromptTemplate> = templates.values.filter { it.name == name }

    override fun save(template: PromptTemplate) {
        templates[template.templateId] = template
    }

    override fun delete(templateId: String) {
        templates.remove(templateId)
    }
}
