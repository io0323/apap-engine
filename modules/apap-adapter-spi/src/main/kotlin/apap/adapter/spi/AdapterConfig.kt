package apap.adapter.spi

import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region

/**
 * 03_基本設計.md 3.3.2 `initialize(config: AdapterConfig, secrets: SecretAccessor)`。
 * `endpoints`/`rateLimits`/`regions` はProvider登録時に検証済みの値
 * （[apap.domain.model.provider.Provider]が保持するものと同じ型）をInfrastructure層がそのまま渡す。
 */
data class AdapterConfig(
    val providerId: ProviderId,
    val endpoints: List<Endpoint>,
    val rateLimits: RateLimits,
    val regions: Set<Region>,
    val options: Map<String, String> = emptyMap(),
    /**
     * このProviderが使うCredentialの参照（09_状態遷移図.mdの4状態を持つ）。
     *
     * ADR-0038: 設計書3.3.2の抜けで、`Provider`は`credentialRefs[]`を持つのにAdapterへ渡す口が
     * 無かった。Adapterは`SecretAccessor.resolve(ref)`に渡す参照を自力で決めるしかなく、
     * adapter-mockが固定のダミー参照を持っていたのはその兆候である。
     *
     * Rotation中はACTIVEとSTANDBYが並存しうるため、Adapterは
     * `state == CredentialState.ACTIVE` のものを使うこと。空リストは「未設定」で、
     * その状態で秘密値を要求するAdapterは初期化エラーにしてよい。
     */
    val credentialRefs: List<CredentialRef> = emptyList(),
) {
    init {
        require(endpoints.isNotEmpty()) { "AdapterConfig.endpoints must not be empty" }
    }
}
