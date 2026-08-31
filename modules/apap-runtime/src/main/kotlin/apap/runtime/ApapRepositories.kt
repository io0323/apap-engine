package apap.runtime

import apap.domain.port.AliasRepository
import apap.domain.port.BudgetRepository
import apap.domain.port.CapabilityRepository
import apap.domain.port.ConversationRepository
import apap.domain.port.HealthLatencyStatsRepository
import apap.domain.port.MemoryRepository
import apap.domain.port.ModelRepository
import apap.domain.port.PolicyRepository
import apap.domain.port.PriceBookRepository
import apap.domain.port.ProviderRepository
import apap.domain.port.QuotaPolicyRepository
import apap.domain.port.QuotaSnapshotRepository
import apap.domain.port.TenantEntitlementRepository
import apap.domain.port.UsageRepository
import apap.infrastructure.persistence.inmemory.InMemoryAliasRepository
import apap.infrastructure.persistence.inmemory.InMemoryBudgetRepository
import apap.infrastructure.persistence.inmemory.InMemoryCapabilityRepository
import apap.infrastructure.persistence.inmemory.InMemoryConversationRepository
import apap.infrastructure.persistence.inmemory.InMemoryHealthLatencyStatsRepository
import apap.infrastructure.persistence.inmemory.InMemoryMemoryRepository
import apap.infrastructure.persistence.inmemory.InMemoryModelRepository
import apap.infrastructure.persistence.inmemory.InMemoryPolicyRepository
import apap.infrastructure.persistence.inmemory.InMemoryPriceBookRepository
import apap.infrastructure.persistence.inmemory.InMemoryProviderRepository
import apap.infrastructure.persistence.inmemory.InMemoryQuotaPolicyRepository
import apap.infrastructure.persistence.inmemory.InMemoryQuotaSnapshotRepository
import apap.infrastructure.persistence.inmemory.InMemoryTenantEntitlementRepository
import apap.infrastructure.persistence.inmemory.InMemoryUsageRepository

/**
 * [ApapEngineBuilder]の`repositories`差替点。全PortにIn-Memory実装を既定で持たせる（03_基本設計.md
 * 3.15「テスト構成」と同じ考え方を、埋込ホストが依存ゼロで動かせる既定構成にも適用したもの）。
 * 一部だけ差し替えたい場合は該当フィールドのみ`copy`すればよい。永続化を永続的にしたい場合は
 * `apap-infrastructure-jdbc`の対応するJdbc*Repositoryへ丸ごと差し替える（この既定値は
 * プロセス再起動でProvider/Model等の構成が失われる——docs/integration/prompt-engine.md参照）。
 */
data class ApapRepositories(
    val providerRepository: ProviderRepository = InMemoryProviderRepository(),
    val modelRepository: ModelRepository = InMemoryModelRepository(),
    val aliasRepository: AliasRepository = InMemoryAliasRepository(),
    val policyRepository: PolicyRepository = InMemoryPolicyRepository(),
    val capabilityRepository: CapabilityRepository = InMemoryCapabilityRepository(),
    val healthLatencyStatsRepository: HealthLatencyStatsRepository = InMemoryHealthLatencyStatsRepository(),
    val quotaSnapshotRepository: QuotaSnapshotRepository = InMemoryQuotaSnapshotRepository(),
    val tenantEntitlementRepository: TenantEntitlementRepository = InMemoryTenantEntitlementRepository(),
    val memoryRepository: MemoryRepository = InMemoryMemoryRepository(),
    val conversationRepository: ConversationRepository = InMemoryConversationRepository(),
    val priceBookRepository: PriceBookRepository = InMemoryPriceBookRepository(),
    val budgetRepository: BudgetRepository = InMemoryBudgetRepository(),
    val usageRepository: UsageRepository = InMemoryUsageRepository(),
    val quotaPolicyRepository: QuotaPolicyRepository = InMemoryQuotaPolicyRepository(),
)
