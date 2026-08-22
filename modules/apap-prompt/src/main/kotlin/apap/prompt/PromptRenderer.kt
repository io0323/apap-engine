package apap.prompt

class PublishedPromptTemplateNotFoundException(
    name: String,
) : NoSuchElementException("No PUBLISHED PromptTemplate found for name=$name")

/** FR-PMT-004: 公開済み[apap.domain.model.prompt.PromptTemplate]を名前で解決し描画する。 */
class PromptRenderer(
    private val engine: TemplateRenderEngine,
    private val templates: PromptTemplateManager,
) {
    fun renderPublished(
        templateName: String,
        variables: Map<String, String>,
        ctx: PromptStageContext,
    ): String {
        val template =
            templates.findPublished(templateName) ?: throw PublishedPromptTemplateNotFoundException(templateName)
        return engine.render(template, variables, ctx)
    }
}
