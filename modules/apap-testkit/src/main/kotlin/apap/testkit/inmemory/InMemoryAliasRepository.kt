package apap.testkit.inmemory

import apap.domain.model.modelcatalog.ModelAlias
import apap.domain.model.vo.AliasId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TenantId
import apap.domain.port.AliasRepository

/**
 * `ModelAlias`（apap-domain 4.3.3）自身はtenantIdを保持しないため（Aggregateの現状のフィールド構成）、
 * `findByName(tenantId, name)`のtenantId引数はこのIn-Memory実装では絞り込みに使えず、
 * `name`のみで一致判定する。テナントスコープの一意性検証は実Infrastructure実装の責務
 * （テーブル設計でtenant_id列を持たせる想定）であり、この制約はテスト用途に限る
 * （CLAUDE.md「ADR化するか否かの判断基準」: 要件充足に影響しないテストダブルの実装詳細）。
 */
class InMemoryAliasRepository : AliasRepository {
    private val aliases = mutableMapOf<AliasId, ModelAlias>()

    override fun findByName(
        tenantId: TenantId,
        name: String,
    ): ModelAlias? = aliases.values.firstOrNull { it.name == name }

    override fun save(alias: ModelAlias) {
        aliases[alias.aliasId] = alias
    }

    override fun listByModel(modelId: ModelId): List<ModelAlias> =
        aliases.values.filter { alias -> alias.targets.any { it.modelId == modelId } }
}
