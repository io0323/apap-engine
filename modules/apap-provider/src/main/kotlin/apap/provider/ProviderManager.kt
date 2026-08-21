package apap.provider

import apap.adapter.spi.ProviderHealthStatus
import apap.domain.event.CredentialValidationFailed
import apap.domain.event.DomainEvent
import apap.domain.event.EventMetadata
import apap.domain.event.ProviderDeleted
import apap.domain.event.ProviderDisabled
import apap.domain.event.ProviderDraining
import apap.domain.event.ProviderEnabled
import apap.domain.event.ProviderRegistered
import apap.domain.event.ProviderValidated
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.Provider
import apap.domain.model.provider.ProviderStatus
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.SemVer
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import apap.domain.port.ProviderRepository
import java.time.Duration

/** 02_システム仕様.md 2.6.2 Provider登録情報。providerId/status/drainStartedAtはProviderManagerが決める。 */
data class RegisterProviderCommand(
    val name: String,
    val adapterPluginId: String,
    val spiVersion: SemVer,
    val endpoints: List<Endpoint>,
    val authType: String,
    val credentialRefs: List<CredentialRef>,
    val rateLimits: RateLimits,
    val priority: Int,
    val regions: Set<Region>,
    val tags: Set<String> = emptySet(),
)

sealed interface ValidationOutcome {
    val provider: Provider

    data class Passed(
        override val provider: Provider,
    ) : ValidationOutcome

    data class Failed(
        override val provider: Provider,
        val reason: String,
    ) : ValidationOutcome
}

/**
 * 02_システム仕様.md 2.3 Provider Manager: Providerライフサイクル
 * （登録/検証/有効化/DRAINING/無効化/削除）のユースケース。判断ロジック（状態遷移の可否）は
 * [Provider]自身（Aggregate）に委譲し、本クラスはリポジトリ永続化・イベント発行・
 * Adapter呼出の手順（10_アクティビティ図.md 10.4）を組み立てるだけに徹する。
 */
@Suppress("TooManyFunctions", "LongParameterList")
class ProviderManager(
    private val providerRepository: ProviderRepository,
    private val eventPublisher: DomainEventPublisher,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val adapterRegistry: AdapterRegistry,
) {
    fun register(command: RegisterProviderCommand): Provider {
        val provider =
            Provider(
                providerId = ProviderId(idGenerator.newId()),
                name = command.name,
                adapterPluginId = command.adapterPluginId,
                spiVersion = command.spiVersion,
                endpoints = command.endpoints,
                authType = command.authType,
                credentialRefs = command.credentialRefs,
                rateLimits = command.rateLimits,
                priority = command.priority,
                regions = command.regions,
                tags = command.tags,
            )
        providerRepository.save(provider)
        publish(
            provider.providerId,
            ProviderRegistered(meta(provider.providerId), provider.providerId, provider.name, provider.adapterPluginId),
        )
        return provider
    }

    /** REGISTERED→VALIDATING。14章に対応するイベントが無いため発行しない。 */
    fun beginValidation(providerId: ProviderId): Provider {
        val provider = requireProvider(providerId).transitionTo(ProviderStatus.VALIDATING)
        providerRepository.save(provider)
        return provider
    }

    /**
     * 15_Provider追加手順.md 15.1 Step5 / 10_アクティビティ図.md 10.4:
     * Credential検証 + 疎通(healthCheck) + Capability申告(plugin.yaml)の突合。
     * 合格してもProviderのstatusはVALIDATINGのまま（ACTIVE化は[enable]の責務）。
     * 不合格ならVALIDATING→REGISTEREDへ差し戻す。ACTIVE化を伴わないため、いずれの場合も
     * この関数自体はスローしない（判定結果を[ValidationOutcome]として返す）。
     */
    suspend fun completeValidation(providerId: ProviderId): ValidationOutcome {
        val provider = requireProvider(providerId)
        check(provider.status == ProviderStatus.VALIDATING) {
            "completeValidation requires status == VALIDATING, but was ${provider.status}"
        }
        val credentialRef = selectCredentialToValidate(provider)
        return if (credentialRef == null) {
            failValidation(provider, "Provider has no ACTIVE or STANDBY CredentialRef to validate")
        } else {
            validateWithAdapter(provider, adapterRegistry.resolve(provider.adapterPluginId), credentialRef)
        }
    }

    private fun selectCredentialToValidate(provider: Provider): CredentialRef? =
        provider.credentialRefs.firstOrNull { it.state == CredentialState.ACTIVE }
            ?: provider.credentialRefs.firstOrNull { it.state == CredentialState.STANDBY }

    private data class ValidationFailure(
        val reason: String,
        val credentialFailure: Boolean = false,
    )

    private suspend fun validateWithAdapter(
        provider: Provider,
        resolved: ResolvedPlugin,
        credentialRef: CredentialRef,
    ): ValidationOutcome {
        val validation = resolved.adapter.validateCredential(credentialRef)
        val health = resolved.adapter.healthCheck()
        val declared = resolved.adapter.supportedCapabilities()
        val manifestCapabilities = resolved.manifest.capabilities

        val failure =
            when {
                !validation.valid ->
                    ValidationFailure(
                        "credential validation failed: ${validation.detail ?: "no detail"}",
                        credentialFailure = true,
                    )
                health.status == ProviderHealthStatus.DOWN ->
                    ValidationFailure("healthCheck reported DOWN: ${health.detail ?: "no detail"}")
                declared != manifestCapabilities ->
                    ValidationFailure(
                        "supportedCapabilities() ($declared) does not match " +
                            "plugin.yaml capabilities ($manifestCapabilities)",
                    )
                else -> null
            }

        return if (failure != null) {
            failValidation(provider, failure.reason, failure.credentialFailure)
        } else {
            passValidation(provider, credentialRef)
        }
    }

    private fun passValidation(
        provider: Provider,
        credentialRef: CredentialRef,
    ): ValidationOutcome.Passed {
        val promotedCredentials =
            provider.credentialRefs.map {
                if (it == credentialRef && it.state == CredentialState.STANDBY) {
                    it.transitionTo(CredentialState.ACTIVE)
                } else {
                    it
                }
            }
        val validated = provider.withCredentialRefs(promotedCredentials)
        providerRepository.save(validated)
        publish(provider.providerId, ProviderValidated(meta(provider.providerId), provider.providerId))
        return ValidationOutcome.Passed(validated)
    }

    private fun failValidation(
        provider: Provider,
        reason: String,
        credentialFailure: Boolean = false,
    ): ValidationOutcome.Failed {
        val reverted = provider.transitionTo(ProviderStatus.REGISTERED)
        providerRepository.save(reverted)
        if (credentialFailure) {
            publish(
                provider.providerId,
                CredentialValidationFailed(meta(provider.providerId), provider.providerId, reason),
            )
        }
        return ValidationOutcome.Failed(reverted, reason)
    }

    /** VALIDATING→ACTIVE、またはSUSPENDED→ACTIVE（復帰）。 */
    fun enable(
        providerId: ProviderId,
        reason: String,
    ): Provider {
        val provider = requireProvider(providerId).transitionTo(ProviderStatus.ACTIVE)
        providerRepository.save(provider)
        publish(providerId, ProviderEnabled(meta(providerId), providerId, reason))
        return provider
    }

    /** ACTIVE→DRAINING。排出タイムアウト判定用に開始時刻を記録する。 */
    fun drain(
        providerId: ProviderId,
        reason: String,
    ): Provider {
        val provider =
            requireProvider(providerId)
                .transitionTo(ProviderStatus.DRAINING)
                .withDrainStartedAt(clock.now())
        providerRepository.save(provider)
        publish(providerId, ProviderDraining(meta(providerId), providerId, reason))
        return provider
    }

    /**
     * DRAINING中のProviderが排出タイムアウト（既定300s）を超過していれば[completeDraining]する。
     * 実際の周期実行（Scheduler）は範囲外。外部から周期的に呼ばれることを想定した口として提供する。
     */
    fun enforceDrainTimeout(
        providerId: ProviderId,
        timeout: Duration = DEFAULT_DRAIN_TIMEOUT,
    ): Provider? {
        val provider = requireProvider(providerId)
        val elapsedSinceDrainStart = provider.drainStartedAt?.let { Duration.between(it, clock.now()) }
        val isDraining = provider.status == ProviderStatus.DRAINING
        val timedOut = isDraining && elapsedSinceDrainStart != null && elapsedSinceDrainStart >= timeout
        return if (timedOut) completeDraining(providerId, "drain_timeout") else null
    }

    /** DRAINING→DISABLED。実行中リクエストの完遂待ちはExecution Engine側の責務（範囲外）。 */
    fun completeDraining(
        providerId: ProviderId,
        reason: String,
    ): Provider {
        val provider = requireProvider(providerId).transitionTo(ProviderStatus.DISABLED)
        providerRepository.save(provider)
        publish(providerId, ProviderDisabled(meta(providerId), providerId, reason))
        return provider
    }

    /** DISABLED→DELETED（論理削除、監査履歴は保持）。 */
    fun delete(providerId: ProviderId): Provider {
        val provider = requireProvider(providerId).transitionTo(ProviderStatus.DELETED)
        providerRepository.save(provider)
        publish(providerId, ProviderDeleted(meta(providerId), providerId))
        return provider
    }

    private fun requireProvider(providerId: ProviderId): Provider =
        checkNotNull(providerRepository.findById(providerId)) { "Provider not found: $providerId" }

    private fun meta(providerId: ProviderId): EventMetadata =
        EventMetadata(
            eventId = idGenerator.newId(),
            occurredAt = clock.now(),
            traceId = idGenerator.newId(),
            tenantId = null,
            aggregateId = providerId.value,
            version = 0,
        )

    private fun publish(
        providerId: ProviderId,
        event: DomainEvent,
    ) {
        providerRepository.saveEvents(providerId, listOf(event))
        eventPublisher.publish(event)
    }

    companion object {
        val DEFAULT_DRAIN_TIMEOUT: Duration = Duration.ofSeconds(300)
    }
}
