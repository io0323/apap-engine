package apap.domain.port

import apap.domain.event.DomainEvent
import apap.domain.model.modelcatalog.ModelAlias
import apap.domain.model.vo.AliasId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TenantId

/** 04_ドメイン設計.md 4.5: ModelAliasはEvent Sourcing対象（`saveEvents`で追記、ADR-0026）。 */
interface AliasRepository {
    fun findByName(
        tenantId: TenantId,
        name: String,
    ): ModelAlias?

    /**
     * [tenantId]は`ModelAlias`自身が保持しないフィールド（4.3.3: 一意性検証はRepository側の責務）
     * だが、永続化実装がテナントスコープの一意性を保証するために必要なため、Portのシグネチャに含める
     * （ADR-0026と同種の判断: 要件充足に必要な情報が欠落していたための最小限の拡張）。
     */
    fun save(
        tenantId: TenantId,
        alias: ModelAlias,
    )

    fun listByModel(modelId: ModelId): List<ModelAlias>

    fun saveEvents(
        id: AliasId,
        events: List<DomainEvent>,
    )
}
