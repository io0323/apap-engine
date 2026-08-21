package apap.adapter.mock

import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.AuthContext
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.testkit.contract.AdapterContractTest
import java.time.Duration

/**
 * `MockProviderAdapter`が`apap-testkit`のAdapter Contract Testに全項目パスすることを確認する
 * （15_Provider追加手順.md 15.1 Step3 / 15.4）。ADR-0015によりtestソースセットからの
 * `apap.testkit`利用が許可されている。
 */
class MockProviderAdapterContractTest : AdapterContractTest() {
    private val chatCapability = CapabilityId("chat")
    private val unsupportedCapability = CapabilityId("embedding")

    /**
     * [errorRequestFor] / [timeoutExceedingRequest] は[AdapterContractTest]から常に[createAdapter]より
     * 先に呼ばれる（呼出順序をソースで確認済み）ため、フック内でこれらのフィールドへ「次に作る
     * Adapterインスタンスへ適用したい挙動」を書き込み、[createAdapter]がそれを読んで
     * [MockAdapterConfig]へ反映する。`@TestFactory`が生成する複数の`DynamicTest`も同一インスタンス内で
     * 「フック呼出→createAdapter呼出」が対になって順次実行されるため、テスト間の干渉は起きない。
     */
    private var pendingForcedErrorCategory: AdapterErrorCategory? = null
    private var pendingExtraDelayMillis: Long = 0

    override fun createAdapter(): ProviderAdapter {
        val config =
            MockAdapterConfig(
                supportedCapabilities = setOf(chatCapability),
                forcedErrorCategory = pendingForcedErrorCategory,
                extraDelayMillis = pendingExtraDelayMillis,
            )
        pendingForcedErrorCategory = null
        pendingExtraDelayMillis = 0
        val adapter = MockProviderAdapter(config)
        adapter.initialize(sampleAdapterConfig(), FixedSecretAccessor)
        return adapter
    }

    override fun supportedCapabilityRequests(): Map<CapabilityId, AdapterRequest> = supportedRequests

    private val supportedRequests: Map<CapabilityId, AdapterRequest> by lazy {
        mapOf(chatCapability to baseRequest(chatCapability))
    }

    override fun unsupportedCapabilityRequest(): AdapterRequest = baseRequest(unsupportedCapability)

    override fun errorRequestFor(category: AdapterErrorCategory): AdapterRequest =
        if (category == AdapterErrorCategory.UNSUPPORTED_CAPABILITY) {
            baseRequest(unsupportedCapability)
        } else {
            pendingForcedErrorCategory = category
            baseRequest(chatCapability)
        }

    override fun secretProbeValue(): String = SECRET_VALUE

    override fun timeoutExceedingRequest(): AdapterRequest {
        pendingExtraDelayMillis = EXTRA_DELAY_MILLIS_FOR_TIMEOUT
        return baseRequest(chatCapability, timeout = Duration.ofMillis(TIMEOUT_MILLIS))
    }

    override fun streamRequest(): AdapterRequest = baseRequest(chatCapability)

    private fun baseRequest(
        capabilityId: CapabilityId,
        timeout: Duration = Duration.ofSeconds(5),
    ) = AdapterRequest(
        capabilityId = capabilityId,
        modelName = "mock-model",
        input = listOf(ContentPart.Text("hello")),
        timeout = timeout,
        authContext = AuthContext(),
    )

    private fun sampleAdapterConfig(): AdapterConfig {
        val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
        return AdapterConfig(
            providerId = ProviderId(SAMPLE_PROVIDER_ID),
            endpoints = listOf(Endpoint("ep1", region, "https://mock.example.internal", 100)),
            rateLimits = RateLimits(rpm = 60, tpm = 100_000, concurrent = 10),
            regions = setOf(region),
        )
    }

    private object FixedSecretAccessor : SecretAccessor {
        override fun resolve(ref: CredentialRef): SecretValue = SecretValue(SECRET_VALUE.toCharArray())
    }

    companion object {
        private const val SECRET_VALUE = "super-secret-test-value"
        private const val SAMPLE_PROVIDER_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        private const val TIMEOUT_MILLIS = 50L
        private const val EXTRA_DELAY_MILLIS_FOR_TIMEOUT = 2000L
    }
}
