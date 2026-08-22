package apap.domain.port

import apap.domain.model.prompt.PromptTemplate

/**
 * `PromptTemplate`（04_ドメイン設計.md 4.3.5）のRepository。3.4のRepository一覧には無い
 * （P1-P5当時はPrompt Contextが未着手だったため）。FR-PMT-004（Prompt Templateのバージョン管理）の
 * 実装に必要なため追加する。
 */
interface PromptTemplateRepository {
    fun findById(templateId: String): PromptTemplate?

    fun findByName(name: String): List<PromptTemplate>

    fun save(template: PromptTemplate)

    fun delete(templateId: String)
}
