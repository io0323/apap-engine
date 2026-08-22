package apap.prompt

import apap.domain.model.prompt.PromptTemplate
import apap.domain.model.prompt.PromptTemplateStatus
import apap.domain.model.prompt.TemplateVariable
import apap.domain.port.IdGenerator
import apap.domain.port.PromptTemplateRepository

class PromptTemplateNotFoundException(
    templateId: String,
) : NoSuchElementException("PromptTemplate not found: $templateId")

/**
 * FR-PMT-004: [PromptTemplateRepository]上のCRUD/バージョニングオーケストレーション。
 * バージョニング不変条件（公開後のbody変更は新バージョン）は[PromptTemplate]自身が強制する
 * （`publish()`/`newRevision()`）ため、本クラスはI/Oを足すだけの薄い層に留める。
 * [PromptTemplate.templateId]はAggregateの識別子であり、[newRevision]後も同一IDのまま
 * バージョンだけが進む（他のAggregate/Repositoryと同じくRepositoryは常に最新状態のみを保持する）。
 */
class PromptTemplateManager(
    private val repository: PromptTemplateRepository,
    private val idGenerator: IdGenerator,
) {
    fun create(
        name: String,
        body: String,
        variables: List<TemplateVariable> = emptyList(),
    ): PromptTemplate {
        val template = PromptTemplate(templateId = idGenerator.newId(), name = name, body = body, variables = variables)
        repository.save(template)
        return template
    }

    fun publish(templateId: String): PromptTemplate = mutate(templateId) { it.publish() }

    fun newRevision(
        templateId: String,
        newBody: String,
        newVariables: List<TemplateVariable>? = null,
    ): PromptTemplate =
        mutate(templateId) { existing ->
            existing.newRevision(newBody, newVariables ?: existing.variables)
        }

    fun archive(templateId: String): PromptTemplate = mutate(templateId) { it.archive() }

    fun get(templateId: String): PromptTemplate {
        val template = repository.findById(templateId) ?: throw PromptTemplateNotFoundException(templateId)
        return template
    }

    /** 同名テンプレートのうちPUBLISHED状態かつ最新versionのものを返す（[PromptRenderer]が使う）。 */
    fun findPublished(name: String): PromptTemplate? =
        repository.findByName(name).filter { it.status == PromptTemplateStatus.PUBLISHED }.maxByOrNull { it.version }

    private fun mutate(
        templateId: String,
        transform: (PromptTemplate) -> PromptTemplate,
    ): PromptTemplate {
        val updated = transform(get(templateId))
        repository.save(updated)
        return updated
    }
}
