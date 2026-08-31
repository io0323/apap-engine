package apap.domain.model.provider

import apap.domain.event.CredentialRotated
import apap.domain.event.CredentialValidationFailed
import apap.domain.event.DomainEvent
import apap.domain.event.ProviderDeleted
import apap.domain.event.ProviderDisabled
import apap.domain.event.ProviderDraining
import apap.domain.event.ProviderEnabled
import apap.domain.event.ProviderHealthChanged
import apap.domain.event.ProviderRegistered
import apap.domain.event.ProviderValidated
import apap.domain.model.UnexpectedEventForAggregateException
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.CredentialState

private const val AGGREGATE_TYPE = "Provider"

/**
 * ADR-0026: [apap.domain.model.reconstruct]と組み合わせ、Providerのstream_idに記録された
 * イベント列からProviderの現在状態を復元する純粋関数。
 *
 * `Provider.transitionTo`/`withDrainStartedAt`が行う遷移合法性チェックはここでは使わず、
 * `copy`で直接フィールドを設定する。理由: REGISTERED→VALIDATING（[apap.provider.ProviderManager.beginValidation]）や
 * VALIDATING→REGISTERED（検証失敗のうちCredential起因でない場合）には14章に対応するイベントが無く
 * 再構築時は経由しない（ADR-0026「意図的に再構築されない遷移的状態」）。そのため再構築中の状態遷移は
 * ライブのコマンド処理と同じ経路を辿らず、`transitionTo`の遷移表チェックに正当に違反しうる
 * （例: 再構築中はREGISTEREDのままProviderEnabledに遭遇し、ACTIVEへ直接飛ぶ）。
 * 遷移の合法性はコマンド決定時（ライブパス）で既に検証済みの前提のため、再構築では再検証しない。
 */
fun applyProviderEvent(
    state: Provider?,
    event: DomainEvent,
): Provider =
    when (event) {
        is ProviderRegistered ->
            Provider(
                providerId = event.providerId,
                name = event.name,
                adapterPluginId = event.adapterPluginId,
                spiVersion = event.spiVersion,
                endpoints = event.endpoints,
                authType = event.authType,
                credentialRefs = event.credentialRefs,
                rateLimits = event.rateLimits,
                priority = event.priority,
                regions = event.regions,
                tags = event.tags,
            )
        is ProviderValidated -> requireState(state, event).promoteCredential(event.credentialVersion)
        is ProviderEnabled -> requireState(state, event).copy(status = ProviderStatus.ACTIVE, drainStartedAt = null)
        is ProviderDraining ->
            requireState(state, event).copy(status = ProviderStatus.DRAINING, drainStartedAt = event.meta.occurredAt)
        is ProviderDisabled ->
            requireState(state, event).copy(status = ProviderStatus.DISABLED, drainStartedAt = null)
        is ProviderDeleted -> requireState(state, event).copy(status = ProviderStatus.DELETED)
        is CredentialRotated -> requireState(state, event).rotateCredential(event)
        is CredentialValidationFailed ->
            requireState(state, event).let { current ->
                if (current.status == ProviderStatus.VALIDATING) {
                    current.copy(status = ProviderStatus.REGISTERED)
                } else {
                    current
                }
            }
        // ProviderはHealthStatusを自身のフィールドとして持たない（ProviderHealthAggregator側の関心事）。
        is ProviderHealthChanged -> requireState(state, event)
        else -> throw UnexpectedEventForAggregateException(AGGREGATE_TYPE, event)
    }

private fun Provider.promoteCredential(credentialVersion: Int): Provider =
    withCredentialRefs(
        credentialRefs.map {
            if (it.version == credentialVersion && it.state == CredentialState.STANDBY) {
                it.transitionTo(CredentialState.ACTIVE)
            } else {
                it
            }
        },
    )

private fun Provider.rotateCredential(event: CredentialRotated): Provider =
    withCredentialRefs(
        credentialRefs.map {
            if (it.version == event.oldVersion && it.state == CredentialState.ACTIVE) {
                it.transitionTo(CredentialState.REVOKED_PENDING)
            } else {
                it
            }
        } + CredentialRef(event.newSecretRef, event.newVersion, CredentialState.ACTIVE),
    )

private fun requireState(
    state: Provider?,
    event: DomainEvent,
): Provider = state ?: throw UnexpectedEventForAggregateException(AGGREGATE_TYPE, event)
