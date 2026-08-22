package apap.prompt

/**
 * Pipelineの3段目（Rendering）。既定Pipelineでは何もしないパススルーとする——
 * `CanonicalRequest`にどの`PromptTemplate`を描画すべきかを示す参照フィールドが無く、
 * このStageだけでは解決できないため（スコープ境界。[PromptRenderer]/[PromptTemplateManager]
 * 自体はCRUD/描画として独立に実装・テスト済みで、`CanonicalRequest`へのテンプレート参照追加は
 * 将来の統合作業とする）。
 */
class RenderingStage : PromptStage {
    override fun apply(
        draft: PromptDraft,
        ctx: PromptStageContext,
    ): PromptDraft = draft
}
