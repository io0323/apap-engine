package apap.adapter.anthropic

import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.AuthContext
import apap.adapter.spi.CapabilityId
import apap.adapter.spi.CredentialRef
import apap.adapter.spi.CredentialState
import apap.adapter.spi.InputMessage
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SecretValue
import apap.adapter.spi.TextContentPart
import apap.adapter.spi.TurnRole
import apap.domain.model.provider.Endpoint
import apap.domain.model.provider.RateLimits
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.ProviderId
import apap.domain.model.vo.Region
import apap.domain.model.vo.RegionCodeTable
import apap.testkit.contract.AdapterContractTest
import apap.testkit.contract.ContentFilteringSurface
import java.time.Duration

/**
 * 15.4「Contract Test全件パス」。実APIではなく[ReplayHttpTransport]の記録に対して回す。
 *
 * **任意フックはすべて実装する**。`AdapterContractTest`の各フックは未実装だと`Assumptions`で
 * スキップされ、緑のまま「検証していない」状態になる——このリポジトリが繰り返し踏んできた
 * 「シグナルの不在を問題の不在と読む」形そのもの。したがってエラー分類・タイムアウト・
 * Stream中断・Credential非漏出のフックを全部埋めている。
 */
class AnthropicAdapterContractTest : AdapterContractTest() {
    override fun createAdapter(): ProviderAdapter = initializedAdapter(ScenarioTransport())

    override fun supportedCapabilityRequests(): Map<CapabilityId, AdapterRequest> =
        AnthropicAdapter.SUPPORTED_CAPABILITIES.associateWith { requestFor(it) }

    override fun unsupportedCapabilityRequest(): AdapterRequest = requestFor(CapabilityId("embedding"))

    /**
     * 8分類のうち、Provider応答から再現できるものを全て埋める。
     * `UNSUPPORTED_CAPABILITY`は[unsupportedCapabilityRequest]側で検証されるため、
     * ここでは申告済みCapabilityに対する応答由来の分類だけを扱う。
     */
    override fun errorRequestFor(category: AdapterErrorCategory): AdapterRequest? =
        when (category) {
            AdapterErrorCategory.TRANSIENT -> errorRequest(ERROR_TRANSIENT)
            AdapterErrorCategory.RATE_LIMITED -> errorRequest(ERROR_RATE_LIMITED)
            AdapterErrorCategory.INVALID_REQUEST -> errorRequest(ERROR_INVALID_REQUEST)
            AdapterErrorCategory.AUTH_ERROR -> errorRequest(ERROR_AUTH)
            AdapterErrorCategory.MODEL_ERROR -> errorRequest(ERROR_MODEL)
            AdapterErrorCategory.PROVIDER_UNAVAILABLE -> errorRequest(ERROR_OVERLOADED)
            // 申告外Capabilityへの呼出で再現できる（専用テストと重複するが、
            // 分類表の網羅としてもここで確かめておく）。
            AdapterErrorCategory.UNSUPPORTED_CAPABILITY -> requestFor(CapabilityId("embedding"))
            // CONTENT_FILTEREDはこの分類表では扱わない。実APIは拒否をHTTP 200の応答
            // （stop_reason=refusal）として返すため、[contentFilteringSurface]で応答側として申告する。
            AdapterErrorCategory.CONTENT_FILTERED -> null
        }

    /**
     * ADR-0037: このProviderは拒否を**HTTP 200の正常応答**（`stop_reason: "refusal"`）で返す。
     * 例外側の分類（2.11）はProviderがエラーステータスを返す場合に予約される。
     */
    override fun contentFilteringSurface(): ContentFilteringSurface =
        ContentFilteringSurface.AsFinishReason(
            requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(
                messages = listOf(userMessage(ScenarioTransport.REFUSAL_MARKER)),
                input = listOf(TextContentPart(ScenarioTransport.REFUSAL_MARKER)),
            ),
        )

    override fun secretProbeValue(): String = SECRET

    override fun timeoutExceedingRequest(): AdapterRequest =
        requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(
            messages = listOf(userMessage(ScenarioTransport.SLOW_MARKER)),
            input = listOf(TextContentPart(ScenarioTransport.SLOW_MARKER)),
            timeout = Duration.ofMillis(TIMEOUT_MILLIS),
        )

    override fun streamRequest(): AdapterRequest = requestFor(AnthropicAdapter.CAPABILITY_STREAMING)

    private fun errorRequest(marker: String): AdapterRequest =
        requestFor(AnthropicAdapter.CAPABILITY_CHAT).copy(
            messages = listOf(userMessage(marker)),
            input = listOf(TextContentPart(marker)),
        )

    private companion object {
        const val SECRET = "contract-test-secret-8f2a"

        const val ERROR_TRANSIENT = "force-error:transient"
        const val ERROR_RATE_LIMITED = "force-error:rate-limited"
        const val ERROR_INVALID_REQUEST = "force-error:invalid-request"
        const val ERROR_AUTH = "force-error:auth"
        const val ERROR_MODEL = "force-error:model"
        const val ERROR_OVERLOADED = "force-error:overloaded"

        /** 1msだとHTTP往復の準備すら間に合わず、遅延の有無に関係なく落ちうるため少しだけ緩める。 */
        const val TIMEOUT_MILLIS = 50L
    }
}

/** Contract Test / 再生テストが共有する組み立て。 */
internal const val TEST_SECRET = "contract-test-secret-8f2a"

internal fun userMessage(text: String) = InputMessage(TurnRole.USER, listOf(TextContentPart(text)))

internal fun requestFor(capabilityId: CapabilityId): AdapterRequest =
    AdapterRequest(
        capabilityId = capabilityId,
        modelName = "test-model-1",
        input = listOf(TextContentPart("hello")),
        messages = listOf(userMessage("hello")),
        timeout = Duration.ofSeconds(30),
        authContext = AuthContext(),
    )

internal fun testConfig(): AdapterConfig {
    val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    return AdapterConfig(
        providerId = ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FZ1"),
        endpoints = listOf(Endpoint("ep1", region, "https://provider.invalid", 100)),
        rateLimits = RateLimits(600, 100_000, 10),
        regions = setOf(region),
        options = mapOf(AnthropicAdapter.CREDENTIAL_REF_OPTION to "test-key-ref"),
    )
}

internal fun testSecrets(secret: String = TEST_SECRET): SecretAccessor =
    object : SecretAccessor {
        override fun resolve(ref: CredentialRef): SecretValue = SecretValue(secret.toCharArray())
    }

internal fun initializedAdapter(transport: HttpTransport): AnthropicAdapter =
    AnthropicAdapter(transportFactory = { transport }).apply { initialize(testConfig(), testSecrets()) }

/** `AdapterConfig.options`を差し替えたAdapter（Structured Outputのモード切替検証に使う）。 */
internal fun adapterWithOptions(
    transport: HttpTransport,
    options: Map<String, String>,
): AnthropicAdapter =
    AnthropicAdapter(transportFactory = { transport }).apply {
        initialize(testConfig().copy(options = testConfig().options + options), testSecrets())
    }

internal fun credentialRef() = CredentialRef("test-key-ref", 1, CredentialState.ACTIVE)
