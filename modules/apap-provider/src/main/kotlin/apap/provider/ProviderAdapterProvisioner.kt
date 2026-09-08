package apap.provider

import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.domain.model.provider.Provider
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.ProviderId
import apap.domain.port.SecretStore
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** 自Provider以外のCredentialを解決しようとした（FR-SEC-005 / NFR-SEC-004違反）。 */
class CredentialAccessDeniedException(
    providerId: ProviderId,
    secretRef: String,
) : Exception(
        "Adapter for provider ${providerId.value} attempted to resolve a credential " +
            "that does not belong to it: $secretRef",
    )

/** そのProviderのAdapterがまだ生成・初期化されていない。 */
class AdapterNotProvisionedException(
    providerId: ProviderId,
) : Exception(
        "No initialized adapter for provider ${providerId.value}. " +
            "The provider must reach VALIDATING (which provisions its adapter) before it can be used.",
    )

/**
 * Providerごとに[ProviderAdapter]のインスタンスを1つ持ち、その生存期間を管理する（ADR-0041）。
 *
 * ## なぜProviderごとなのか
 *
 * 従来は`AdapterRegistry`が**pluginIdをキー**にしており、1つのPluginを複数のProviderが
 * 参照すると**同一インスタンスが共有**されていた。ところがProviderは
 * `endpoints` / `credentialRefs` / `rateLimits` / `regions` を**Providerごとに**持つため、
 * 共有インスタンスではどの設定で動くかが決まらない。これは配線の穴であると同時に
 * セキュリティ要件の穴でもある——共有インスタンスは全Providerの設定に触れるため、
 * FR-SEC-005（Provider Isolation）とNFR-SEC-004（Adapterは自ProviderのCredentialのみ
 * アクセス可能）が**原理的に成立しない**。
 *
 * Plugin（ロード済みクラス・ClassLoader）は共有したままインスタンスだけを分ける。
 * 分離ClassLoaderのコストを増やさずにProvider境界を作れる。
 *
 * ## 生存期間
 *
 * | 契機 | 動作 |
 * |---|---|
 * | VALIDATING | [provision]で生成し`initialize(config, secrets)`を呼ぶ（`validateCredential`/`healthCheck`に要る） |
 * | 設定変更・Credentialローテーション | 設定の指紋が変われば作り直す（**再起動を要しない**） |
 * | DISABLED / DELETED | [release]で`shutdown()`して破棄 |
 *
 * ## Credentialのスコープ
 *
 * 各インスタンスへ渡す[SecretAccessor]は、そのProviderの`credentialRefs`に**スコープされる**。
 * 他Providerの参照を渡されたら[CredentialAccessDeniedException]で失敗する。
 * 「Adapterが自分の分だけを引ける」ことを、規約ではなく機構で保証する。
 */
class ProviderAdapterProvisioner(
    private val factory: ProviderAdapterFactory,
    private val secretStore: SecretStore,
) : AdapterRegistry {
    private val instances = ConcurrentHashMap<ProviderId, Entry>()

    private data class Entry(
        val resolved: ResolvedPlugin,
        /** 再生成が要るかの判定材料。Providerのうち、Adapterの動作を決める部分だけを見る。 */
        val fingerprint: Fingerprint,
    )

    private data class Fingerprint(
        val pluginId: String,
        val endpoints: List<String>,
        val rateLimits: String,
        val regions: Set<String>,
        val credentialRefs: List<String>,
    )

    override fun resolve(providerId: ProviderId): ResolvedPlugin =
        instances[providerId]?.resolved ?: throw AdapterNotProvisionedException(providerId)

    /**
     * [provider]専用のAdapterインスタンスを用意する。既に同じ設定で用意済みなら何もしない。
     *
     * 設定（[Fingerprint]）が変わっていれば古いインスタンスを`shutdown()`してから作り直す。
     * これによりCredentialローテーション（9.7）や設定変更が**プロセス再起動なしに**反映される。
     */
    fun provision(provider: Provider): ResolvedPlugin {
        val fingerprint = fingerprintOf(provider)
        // computeで丸ごと囲うのは、同じProviderへの同時呼出（有効化とローテーションが重なる等）で
        // インスタンスが2つ生まれ、片方がshutdownされないまま漏れるのを防ぐため。
        val entry =
            instances.compute(provider.providerId) { providerId, existing ->
                if (existing != null && existing.fingerprint == fingerprint) {
                    existing
                } else {
                    // 設定が変わった。古いインスタンスは確実に畳んでから差し替える。
                    existing?.let { shutdownQuietly(providerId, it.resolved.adapter) }
                    val created = factory.instantiate(provider.adapterPluginId)
                    created.adapter.initialize(configOf(provider), scopedSecrets(provider))
                    logger.debug(
                        "provisioned adapter for provider={} plugin={}",
                        providerId.value,
                        provider.adapterPluginId,
                    )
                    Entry(created, fingerprint)
                }
            }
        // computeのマッピング関数はnullを返さないため、ここでnullにはならない。
        return checkNotNull(entry).resolved
    }

    /** DISABLED / DELETED で呼ぶ。既に無ければ何もしない（冪等）。 */
    fun release(providerId: ProviderId) {
        val removed = instances.remove(providerId) ?: return
        shutdownQuietly(providerId, removed.resolved.adapter)
    }

    /** 現在インスタンスを保持しているProvider（テストと運用診断用）。 */
    fun provisionedProviders(): Set<ProviderId> = instances.keys.toSet()

    private fun shutdownQuietly(
        providerId: ProviderId,
        adapter: ProviderAdapter,
    ) {
        runCatching { adapter.shutdown() }
            .onFailure { e ->
                logger.warn("adapter.shutdown() failed for provider={}: {}", providerId.value, e.message, e)
            }
    }

    private fun configOf(provider: Provider): AdapterConfig =
        AdapterConfig(
            providerId = provider.providerId,
            endpoints = provider.endpoints,
            rateLimits = provider.rateLimits,
            regions = provider.regions,
            credentialRefs = provider.credentialRefs,
        )

    private fun scopedSecrets(provider: Provider): SecretAccessor = ProviderScopedSecretAccessor(provider, secretStore)

    private fun fingerprintOf(provider: Provider): Fingerprint =
        Fingerprint(
            pluginId = provider.adapterPluginId,
            endpoints = provider.endpoints.map { "${it.endpointId}|${it.baseUrl}|${it.region.code}|${it.weight}" },
            rateLimits = "${provider.rateLimits.rpm}|${provider.rateLimits.tpm}|${provider.rateLimits.concurrent}",
            regions = provider.regions.map { it.code }.toSet(),
            // 状態まで含める。ローテーションでACTIVEが入れ替われば作り直す必要がある。
            credentialRefs = provider.credentialRefs.map { "${it.secretRef}|${it.version}|${it.state}" },
        )

    /**
     * そのProviderのCredentialだけを解決できる[SecretAccessor]（NFR-SEC-004）。
     *
     * 参照の一致は`secretRef`と`version`で見る——`state`はローテーション中に変わるため、
     * 状態違いで拒否すると正当な解決まで落ちる。値は[SecretValue]として返し、
     * このクラスは**保持しない**（不変条件4）。
     */
    private class ProviderScopedSecretAccessor(
        provider: Provider,
        private val secretStore: SecretStore,
    ) : SecretAccessor {
        private val providerId = provider.providerId
        private val allowed: Set<String> = provider.credentialRefs.map { key(it) }.toSet()

        override fun resolve(ref: CredentialRef): SecretValue {
            if (key(ref) !in allowed) throw CredentialAccessDeniedException(providerId, ref.secretRef)
            return SecretValue(secretStore.resolve(ref))
        }

        private fun key(ref: CredentialRef): String = "${ref.secretRef}|${ref.version}"
    }

    private companion object {
        val logger = LoggerFactory.getLogger(ProviderAdapterProvisioner::class.java)
    }
}
