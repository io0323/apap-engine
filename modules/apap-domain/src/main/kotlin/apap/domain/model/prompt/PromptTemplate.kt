package apap.domain.model.prompt

import apap.domain.model.IllegalStateTransitionException

enum class PromptTemplateStatus { DRAFT, PUBLISHED, ARCHIVED }

class PromptTemplateNotDraftException(
    templateId: String,
    status: PromptTemplateStatus,
) : IllegalStateTransitionException("PromptTemplate $templateId must be DRAFT to publish, was $status")

class PromptTemplateNotPublishedException(
    templateId: String,
    status: PromptTemplateStatus,
) : IllegalStateTransitionException("PromptTemplate $templateId must be PUBLISHED to start a new revision, was $status")

data class TemplateVariable(
    val name: String,
    val required: Boolean = true,
    val defaultValue: String? = null,
) {
    init {
        require(name.isNotBlank()) { "TemplateVariable.name must not be blank" }
    }
}

/**
 * 04_ドメイン設計.md 4.3.5 PromptTemplate Aggregate（Root）。
 * 不変条件: 公開後のbody変更は新バージョン。[newRevision]経由でのみbodyを改版でき、
 * PUBLISHED中のインスタンスを直接copyでbody変更する経路は業務メソッドとして提供しない。
 */
data class PromptTemplate(
    val templateId: String,
    val name: String,
    val version: Int = 1,
    val body: String,
    val variables: List<TemplateVariable> = emptyList(),
    val status: PromptTemplateStatus = PromptTemplateStatus.DRAFT,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(body.isNotBlank()) { "body must not be blank" }
        require(version >= 1) { "version must be >= 1: $version" }
    }

    fun publish(): PromptTemplate {
        if (status != PromptTemplateStatus.DRAFT) {
            throw PromptTemplateNotDraftException(templateId, status)
        }
        return copy(status = PromptTemplateStatus.PUBLISHED)
    }

    fun newRevision(
        newBody: String,
        newVariables: List<TemplateVariable> = variables,
    ): PromptTemplate {
        if (status != PromptTemplateStatus.PUBLISHED) {
            throw PromptTemplateNotPublishedException(templateId, status)
        }
        return copy(
            body = newBody,
            variables = newVariables,
            version = version + 1,
            status = PromptTemplateStatus.DRAFT,
        )
    }

    fun archive(): PromptTemplate = copy(status = PromptTemplateStatus.ARCHIVED)
}
