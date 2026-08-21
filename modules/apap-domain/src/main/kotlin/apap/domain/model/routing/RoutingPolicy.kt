package apap.domain.model.routing

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.TenantId

/** 02_システム仕様.md 2.5.3 / 03_基本設計.md 3.14。 */
enum class PolicyScope { PLATFORM, TENANT, WORKFLOW, USER }

enum class PolicyEffect { ALLOW, DENY }

enum class PolicyStatus { DRAFT, ACTIVE, ARCHIVED }

/** 03_基本設計.md 3.14 PolicyRule.target。 */
data class PolicyRuleTarget(
    val providers: Set<ProviderId> = emptySet(),
    val models: Set<ModelId> = emptySet(),
    val capabilities: Set<CapabilityId> = emptySet(),
    val regions: Set<Region> = emptySet(),
)

/** 03_基本設計.md 3.14 PolicyRule.override。 */
data class PolicyOverride(
    val weights: Map<String, Double>? = null,
    val fallbackDepth: Int? = null,
    val cacheTtlSeconds: Long? = null,
    val retryMax: Int? = null,
)

/**
 * 03_基本設計.md 3.14: `condition`はExpression（例: cost.estimated > 0.05）。
 * 条件式の評価エンジンはDomain層の範囲外（PolicyResolutionServiceの評価対象として文字列のまま保持）。
 */
data class PolicyRule(
    val effect: PolicyEffect,
    val target: PolicyRuleTarget,
    val condition: String? = null,
    val override: PolicyOverride? = null,
)

/**
 * 04_ドメイン設計.md 4.3.5 RoutingPolicy Aggregate（Root）。
 * 不変条件: DENY削除は新バージョン発行のみ（履歴保持）。ルールを直接書き換えるmutatorは公開せず、
 * [publishNewVersion] によってのみ新しいルール集合を持つ新バージョンを得られるようにすることで、
 * 「同一バージョン内でDENYを削除する」操作自体を構造的に禁止する。
 */
data class RoutingPolicy(
    val policyId: String,
    val scope: PolicyScope,
    val tenantId: TenantId? = null,
    val workflowId: String? = null,
    val rules: List<PolicyRule>,
    val version: Int = 1,
    val status: PolicyStatus = PolicyStatus.ACTIVE,
) {
    init {
        require(version >= 1) { "version must be >= 1: $version" }
        if (scope == PolicyScope.TENANT || scope == PolicyScope.WORKFLOW) {
            require(tenantId != null) { "tenantId is required for scope=$scope" }
        }
        if (scope == PolicyScope.WORKFLOW) {
            require(!workflowId.isNullOrBlank()) { "workflowId is required for scope=WORKFLOW" }
        }
    }

    fun publishNewVersion(newRules: List<PolicyRule>): RoutingPolicy = copy(rules = newRules, version = version + 1)

    fun archive(): RoutingPolicy = copy(status = PolicyStatus.ARCHIVED)
}
