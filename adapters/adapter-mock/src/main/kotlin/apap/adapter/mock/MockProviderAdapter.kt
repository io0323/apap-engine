package apap.adapter.mock

import apap.adapter.spi.AdapterChunk
import apap.adapter.spi.AdapterChunkType
import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.AdapterErrorCategory
import apap.adapter.spi.AdapterException
import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.AdapterResponse
import apap.adapter.spi.AuthContext
import apap.adapter.spi.CapabilityConstraints
import apap.adapter.spi.CapabilityId
import apap.adapter.spi.ContentPart
import apap.adapter.spi.CredentialRef
import apap.adapter.spi.CredentialState
import apap.adapter.spi.DiscoveredModel
import apap.adapter.spi.FinishReason
import apap.adapter.spi.HealthResult
import apap.adapter.spi.Period
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.ProviderCost
import apap.adapter.spi.ProviderToolFormat
import apap.adapter.spi.ProviderUsage
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SemVer
import apap.adapter.spi.TextContentPart
import apap.adapter.spi.TokenCount
import apap.adapter.spi.ToolDefinition
import apap.adapter.spi.ValidationResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * 15_Provider追加手順.md / 16_拡張ポイント.md 16.1: 決定的応答を返すテスト用Adapter。
 * `apap-adapter-spi`にのみ依存し（01_CLAUDE.md 不変条件2）、実Providerの知識を一切持たない。
 *
 * `initialize`で受け取った[SecretAccessor]は、[MockAdapterConfig]自体には現れない固定の
 * ダミー[CredentialRef]（実Providerと違い、AdapterConfigはどのCredentialRefを使うかを明示しないため、
 * このAdapter自身が１つ保持する。CLAUDE.md「ADR化するか否かの判断基準」: 要件充足に影響しない
 * 実装詳細のためKDocのみで補う）で都度`resolve()`し、解決した秘密値を例外・ログへ絶対に含めない。
 *
 * ProviderAdapter SPI(3.3.2)の全メソッドを実装するため、関数数は契約そのものに由来する
 * （`TooManyFunctions`を抑制する）。
 */
@Suppress("TooManyFunctions")
class MockProviderAdapter(
    private val config: MockAdapterConfig = MockAdapterConfig(),
) : ProviderAdapter {
    private var secrets: SecretAccessor? = null
    private var initialized = false

    override fun initialize(
        config: AdapterConfig,
        secrets: SecretAccessor,
    ) {
        this.secrets = secrets
        initialized = true
    }

    override fun shutdown() {
        initialized = false
        secrets = null
    }

    override fun spiVersion(): SemVer = SemVer(1, 0, 0)

    override fun supportedCapabilities(): Set<CapabilityId> = config.supportedCapabilities

    override fun capabilityConstraints(capabilityId: CapabilityId): CapabilityConstraints =
        CapabilityConstraints(
            streamable = true,
        )

    override suspend fun authenticate(): AuthContext {
        resolveCredentialSafely()
        return AuthContext()
    }

    override suspend fun validateCredential(ref: CredentialRef): ValidationResult {
        requireSecrets().resolve(ref).use { }
        return ValidationResult(valid = true)
    }

    override suspend fun execute(request: AdapterRequest): AdapterResponse {
        ensureSupported(request.capabilityId)
        resolveCredentialSafely()
        return try {
            withTimeout(request.timeout.toMillis()) {
                simulateLatencyAndFailure(request)
                AdapterResponse(
                    output = listOf(TextContentPart("mock response for ${request.capabilityId.value}")),
                    finishReason = FinishReason.COMPLETED,
                    usage = config.usage,
                )
            }
        } catch (e: TimeoutCancellationException) {
            throw AdapterException(
                AdapterErrorCategory.TRANSIENT,
                "mock execute exceeded timeout of ${request.timeout}",
                cause = e,
            )
        }
    }

    override suspend fun executeStream(request: AdapterRequest): ProviderAdapter.AdapterStream {
        ensureSupported(request.capabilityId)
        resolveCredentialSafely()
        forcedErrorCategory(request)?.let { category ->
            throw AdapterException(category, "mock executeStream forced error: $category")
        }
        val chunks = config.streamChunks.ifEmpty { defaultStreamChunks() }
        return MockAdapterStream(chunks, config.latency.toMillis())
    }

    override fun translateTools(tools: List<ToolDefinition>): ProviderToolFormat =
        ProviderToolFormat(
            payload = tools.map { it.name },
        )

    override suspend fun discoverModels(): List<DiscoveredModel> = emptyList()

    override suspend fun healthCheck(): HealthResult {
        if (config.latency > java.time.Duration.ZERO) delay(config.latency.toMillis())
        return config.healthResult
    }

    override suspend fun fetchUsage(period: Period): ProviderUsage? = null

    override suspend fun fetchCost(period: Period): ProviderCost? = null

    override suspend fun estimateTokens(input: List<ContentPart>): TokenCount? = config.estimateTokensValue

    private fun ensureSupported(capabilityId: CapabilityId) {
        if (capabilityId !in config.supportedCapabilities) {
            throw AdapterException(
                AdapterErrorCategory.UNSUPPORTED_CAPABILITY,
                "mock adapter does not support capability: ${capabilityId.value}",
            )
        }
    }

    private suspend fun simulateLatencyAndFailure(request: AdapterRequest) {
        val extraDelayMillis =
            request.traceHeaders[MockAdapterHeaders.EXTRA_DELAY_MILLIS]?.toLongOrNull() ?: 0L
        val totalDelayMillis = config.latency.toMillis() + extraDelayMillis
        if (totalDelayMillis > 0) delay(totalDelayMillis)
        forcedErrorCategory(request)?.let { category ->
            throw AdapterException(category, "mock execute forced error: $category")
        }
    }

    private fun forcedErrorCategory(request: AdapterRequest): AdapterErrorCategory? =
        request.traceHeaders[MockAdapterHeaders.FORCED_ERROR_CATEGORY]?.let { AdapterErrorCategory.valueOf(it) }

    private fun resolveCredentialSafely() {
        requireSecrets().resolve(DUMMY_CREDENTIAL_REF).use { /* 解決するだけで、値は一切外部へ出さない */ }
    }

    private fun requireSecrets(): SecretAccessor =
        checkNotNull(secrets) {
            "MockProviderAdapter.initialize() was not called"
        }

    private class MockAdapterStream(
        private val chunks: List<AdapterChunk>,
        private val latencyMillis: Long,
    ) : ProviderAdapter.AdapterStream {
        private var index = 0
        private var cancelled = false

        override suspend fun next(): AdapterChunk? {
            if (cancelled || index >= chunks.size) return null
            if (latencyMillis > 0) delay(latencyMillis)
            return chunks[index++]
        }

        override fun cancel() {
            cancelled = true
        }
    }

    companion object {
        private val DUMMY_CREDENTIAL_REF = CredentialRef("mock-secret-ref", 1, CredentialState.ACTIVE)

        private fun defaultStreamChunks(): List<AdapterChunk> =
            listOf(
                AdapterChunk(type = AdapterChunkType.MESSAGE_START, index = 0),
                AdapterChunk(type = AdapterChunkType.CONTENT_DELTA, index = 1, delta = TextContentPart("mock")),
                AdapterChunk(type = AdapterChunkType.MESSAGE_END, index = 2),
            )
    }
}
