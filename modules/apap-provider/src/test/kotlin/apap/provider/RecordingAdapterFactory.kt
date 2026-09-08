package apap.provider

import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.plugin.PluginManifest
import apap.adapter.spi.plugin.SemVerRange
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.SemVer
import apap.domain.port.SecretStore

/**
 * `initialize` / `shutdown` に何が渡り、いつ呼ばれたかを覚えておくだけのAdapter（ADR-0041のテスト用）。
 *
 * 振る舞いは[FakeProviderAdapter]へ委譲する。ライフサイクルの2メソッドだけを観測したいのであって、
 * Adapterの機能を作り直したいわけではない。
 */
class RecordingAdapter : ProviderAdapter by FakeProviderAdapter() {
    var config: AdapterConfig? = null
        private set
    var secrets: SecretAccessor? = null
        private set
    var initializeCount = 0
        private set
    var shutdownCount = 0
        private set

    val initialized: Boolean get() = initializeCount > 0
    val shutdownCalled: Boolean get() = shutdownCount > 0

    override fun initialize(
        config: AdapterConfig,
        secrets: SecretAccessor,
    ) {
        this.config = config
        this.secrets = secrets
        initializeCount++
    }

    override fun shutdown() {
        shutdownCount++
    }
}

/**
 * 呼ばれるたびに**新しい**[RecordingAdapter]を返す（本番の`PluginManager.newAdapter`と同じ約束）。
 * 生成順に[created]へ積むので、テストは「2つのProviderが別インスタンスを得たか」を直接見られる。
 */
class RecordingAdapterFactory(
    private val capabilities: Set<CapabilityId> = setOf(CapabilityId("chat")),
) : ProviderAdapterFactory {
    val created = mutableListOf<RecordingAdapter>()

    override fun instantiate(pluginId: String): ResolvedPlugin {
        val adapter = RecordingAdapter()
        created += adapter
        return ResolvedPlugin(adapter, manifestOf(pluginId))
    }

    private fun manifestOf(pluginId: String) =
        PluginManifest(
            pluginId = pluginId,
            version = SemVer(1, 0, 0),
            spiVersionRange = SemVerRange.parse(">=1.0"),
            entryPoint = "test.Entry",
            capabilities = capabilities,
            authTypes = setOf("api_key"),
            signature = "sig",
        )
}

/** `<secretRef>` を `secret-<secretRef>` として返すだけの[SecretStore]。 */
class MapSecretStore : SecretStore {
    override fun resolve(ref: CredentialRef): CharArray = "secret-${ref.secretRef}".toCharArray()

    override fun store(
        ref: CredentialRef,
        value: CharArray,
    ) = error("MapSecretStore.store is not used by these tests")
}
