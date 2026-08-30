package apap.infrastructure.persistence.inmemory

import apap.domain.model.modelcatalog.ModelAlias
import apap.domain.model.vo.AliasId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TenantId
import apap.domain.port.AliasRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * [AliasRepository]の本番用In-Memory実装（単一プロセス埋込利用の既定、ADR-0001）。
 *
 * `ModelAlias`自身はtenantIdを保持しないため（Aggregateの現状のフィールド構成）、
 * [findByName]のtenantId引数はこの実装では絞り込みに使えず、`name`のみで一致判定する。
 * テナントスコープの一意性検証はJDBC実装の責務（テーブル設計でtenant_id列を持たせる）。
 * 要件充足に影響しない実装判断のためADR化せずここに根拠を記す。
 */
class InMemoryAliasRepository : AliasRepository {
    private val aliases = ConcurrentHashMap<AliasId, ModelAlias>()

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
