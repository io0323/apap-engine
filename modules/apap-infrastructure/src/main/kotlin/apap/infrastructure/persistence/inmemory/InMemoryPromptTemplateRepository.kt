package apap.infrastructure.persistence.inmemory

import apap.domain.model.prompt.PromptTemplate
import apap.domain.port.PromptTemplateRepository
import java.util.concurrent.ConcurrentHashMap

/** [PromptTemplateRepository]の本番用In-Memory実装。 */
class InMemoryPromptTemplateRepository : PromptTemplateRepository {
    private val templates = ConcurrentHashMap<String, PromptTemplate>()

    override fun findById(templateId: String): PromptTemplate? = templates[templateId]

    override fun findByName(name: String): List<PromptTemplate> = templates.values.filter { it.name == name }

    override fun save(template: PromptTemplate) {
        templates[template.templateId] = template
    }

    override fun delete(templateId: String) {
        templates.remove(templateId)
    }
}
