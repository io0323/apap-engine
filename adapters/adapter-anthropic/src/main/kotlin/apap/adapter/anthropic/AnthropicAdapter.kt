package apap.adapter.anthropic

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
import apap.adapter.spi.HealthResult
import apap.adapter.spi.Period
import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.ProviderCost
import apap.adapter.spi.ProviderHealthStatus
import apap.adapter.spi.ProviderToolFormat
import apap.adapter.spi.ProviderUsage
import apap.adapter.spi.SecretAccessor
import apap.adapter.spi.SemVer
import apap.adapter.spi.TokenCount
import apap.adapter.spi.ToolDefinition
import apap.adapter.spi.ValidationResult
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant

/**
 * 実Provider（Messages API）向けの[ProviderAdapter]実装。
 *
 * 15.1 Step1の実装規約に従う:
 * - Provider固有のURL・認証・形式・エラーコードはこのモジュール内へ閉じ込める
 * - 例外は必ず[AdapterException]の8分類へ変換する（[ErrorMapper]）
 * - Credentialは[SecretAccessor.resolve]で**都度**取得し、フィールドに保持しない
 * - Provider側Retry機構は使わない（[KtorHttpTransport]のKDoc参照）
 * - Streamは[cancel]でProvider接続を確実に切る
 * - [AdapterRequest.timeout]を厳守する
 *
 * ## Credentialの参照解決について（SPIの制約に対する回避策）
 *
 * `AdapterConfig`はどの[CredentialRef]を使うべきかを**持っていない**。`authenticate()`も
 * 引数を取らない。そのためAdapterは自力で参照名を決めるほかなく、ここでは
 * `AdapterConfig.options["credential.ref"]`（既定 [DEFAULT_CREDENTIAL_REF_NAME]）から
 * 組み立てている。adapter-mockが固定のダミー参照を持っているのと同じ回避で、
 * SPIの不足として docs/adapter-spi-findings.md に記録している。
 */
@Suppress("TooManyFunctions")
class AnthropicAdapter(
    private val transportFactory: (String) -> HttpTransport = { baseUrl -> KtorHttpTransport(baseUrl) },
    private val clock: () -> Instant = Instant::now,
) : ProviderAdapter {
    private val mapper = ObjectMapper()

    private var config: AdapterConfig? = null
    private var secrets: SecretAccessor? = null
    private var transport: HttpTransport? = null

    // --- ライフサイクル ---------------------------------------------------------------------

    override fun initialize(
        config: AdapterConfig,
        secrets: SecretAccessor,
    ) {
        this.config = config
        this.secrets = secrets
        this.transport = transportFactory(baseUrlOf(config))
    }

    override fun shutdown() {
        transport?.close()
        transport = null
        secrets = null
        config = null
    }

    override fun spiVersion(): SemVer = SemVer(1, 0, 0)

    // --- 能力申告 ---------------------------------------------------------------------------

    override fun supportedCapabilities(): Set<CapabilityId> = SUPPORTED_CAPABILITIES

    override fun capabilityConstraints(capabilityId: CapabilityId): CapabilityConstraints =
        when (capabilityId) {
            CAPABILITY_CHAT ->
                CapabilityConstraints(
                    maxInputTokens = DEFAULT_CONTEXT_WINDOW,
                    maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS,
                    streamable = true,
                    supportsTools = true,
                    // capabilityConstraintsの固定フィールドで表現できない制約はextraへ入れる。
                    // 何がはみ出したかは findings に記録している。
                    extra =
                        mapOf(
                            "modalities.input" to "text,image",
                            "modalities.output" to "text",
                            "messages.must_alternate" to "true",
                            "max_tokens.required" to "true",
                        ),
                )
            CAPABILITY_STREAMING ->
                CapabilityConstraints(streamable = true, supportsTools = true)
            CAPABILITY_TOOL_CALLING ->
                CapabilityConstraints(streamable = true, supportsTools = true)
            else -> CapabilityConstraints()
        }

    // --- 認証 -------------------------------------------------------------------------------

    /**
     * APIキー方式のため事前のトークン取得は不要で、**呼出ごとのヘッダ組立**が認証の実体になる。
     * ここで鍵を取って[AuthContext]へ載せることは**しない**——AuthContextはexecuteまで
     * 持ち回られるため、載せた時点でCredentialがメモリ上を移動し、不変条件4の「保持しない」に反する。
     */
    override suspend fun authenticate(): AuthContext {
        // 参照が解決できること（＝鍵が引けること）だけを確認し、値は即座に捨てる。
        resolveSecret { }
        return AuthContext(expiresAt = null)
    }

    override suspend fun validateCredential(ref: CredentialRef): ValidationResult {
        val call =
            HttpCall(
                method = "GET",
                path = MODELS_PATH,
                headers = headersFor(ref, stream = false),
                body = null,
                timeout = VALIDATE_TIMEOUT,
            )
        return runCatching { requireTransport().send(call) }
            .fold(
                onSuccess = { reply ->
                    if (reply.status in SUCCESS_RANGE) {
                        ValidationResult(valid = true)
                    } else {
                        // 失敗理由にCredentialは載せない（statusとProvider側の分類のみ）。
                        ValidationResult(valid = false, detail = "status=${reply.status}")
                    }
                },
                onFailure = { ValidationResult(valid = false, detail = it::class.simpleName) },
            )
    }

    // --- 実行 -------------------------------------------------------------------------------

    override suspend fun execute(request: AdapterRequest): AdapterResponse {
        ensureSupported(request.capabilityId)
        val body = buildBody(request, stream = false)
        val reply = callWithTimeout(request, MESSAGES_PATH, body, stream = false)
        if (reply.status !in SUCCESS_RANGE) throw ErrorMapper.toException(reply)
        return runCatching { ResponseMapper.toResponse(reply.body) }
            .getOrElse { throw malformedResponse(it) }
    }

    override suspend fun executeStream(request: AdapterRequest): ProviderAdapter.AdapterStream {
        ensureSupported(request.capabilityId)
        val body = buildBody(request, stream = true)
        val call = messagesCall(request, body, stream = true)
        val source =
            runCatching { requireTransport().openEventStream(call) }
                .getOrElse { throw ErrorMapper.toTransport(it, "executeStream") }
        return AnthropicAdapterStream(source)
    }

    // --- Tool変換 ---------------------------------------------------------------------------

    override fun translateTools(tools: List<ToolDefinition>): ProviderToolFormat =
        ProviderToolFormat(payload = mapper.writeValueAsString(RequestBodyBuilder.toolsArray(tools)))

    // --- 管理系 -----------------------------------------------------------------------------

    override suspend fun discoverModels(): List<DiscoveredModel> {
        val call =
            HttpCall("GET", MODELS_PATH, headersForCurrentCredential(sse = false), null, DISCOVERY_TIMEOUT)
        val reply =
            runCatching { requireTransport().send(call) }
                .getOrElse { throw ErrorMapper.toTransport(it, "discoverModels") }
        if (reply.status !in SUCCESS_RANGE) throw ErrorMapper.toException(reply)
        val root = runCatching { mapper.readTree(reply.body) }.getOrElse { throw malformedResponse(it) }
        return root.path("data").mapNotNull { node ->
            val id = node.path("id").asText(null) ?: return@mapNotNull null
            DiscoveredModel(
                modelName = id,
                // 一覧APIはバージョンを別フィールドで返さない。IDが版を含む体系のためIDを版とする。
                version = id,
                capabilities = SUPPORTED_CAPABILITIES,
                // 一覧APIはcontext window / max outputを返さない（findings参照）。
                contextWindow = DEFAULT_CONTEXT_WINDOW,
                maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS,
                regions =
                    config
                        ?.regions
                        ?.map { it.code }
                        ?.toSet()
                        .orEmpty(),
            )
        }
    }

    /**
     * 専用のヘルスエンドポイントが無いため、モデル一覧の疎通で代用する。
     * 認証エラーはProvider自体はUPだがこのCredentialでは使えない状態なので[ProviderHealthStatus.DEGRADED]とする。
     */
    override suspend fun healthCheck(): HealthResult {
        val started = clock()
        val call = HttpCall("GET", MODELS_PATH, headersForCurrentCredential(sse = false), null, HEALTH_TIMEOUT)
        val reply = runCatching { requireTransport().send(call) }.getOrNull()
        val latency = Duration.between(started, clock())
        return when {
            reply == null -> HealthResult(ProviderHealthStatus.DOWN, latency, "transport failure")
            reply.status in SUCCESS_RANGE -> HealthResult(ProviderHealthStatus.UP, latency)
            reply.status in AUTH_STATUSES -> HealthResult(ProviderHealthStatus.DEGRADED, latency, "auth rejected")
            reply.status >= SERVER_ERROR_STATUS ->
                HealthResult(ProviderHealthStatus.DOWN, latency, "status=${reply.status}")
            else -> HealthResult(ProviderHealthStatus.DEGRADED, latency, "status=${reply.status}")
        }
    }

    /**
     * 使用量集計APIは**管理用の別Credential**を要し、Providerごとに1本しか参照を持てない
     * 現在のSPIでは呼べない。呼べないことを`null`で表す（15.1 Step1「なければnull返却可」）。
     */
    override suspend fun fetchUsage(period: Period): ProviderUsage? = null

    override suspend fun fetchCost(period: Period): ProviderCost? = null

    /**
     * ADR-0010: 正確なトークナイザを提供できる場合のみ実装する。
     * このProviderはトークン数**計算APIを持つがネットワーク往復を伴う**ため、
     * 推定のたびに実APIを叩くことになる。見積り経路にProvider呼出を持ち込む影響が
     * 大きいため実装せず、コア側のHEURISTIC推定（安全マージン15%、ADR-0009）に委ねる。
     * 判断の詳細は findings に記録している。
     */
    override suspend fun estimateTokens(input: List<ContentPart>): TokenCount? = null

    // --- 内部 -------------------------------------------------------------------------------

    private fun buildBody(
        request: AdapterRequest,
        stream: Boolean,
    ): String =
        try {
            RequestBodyBuilder.build(request, stream, DEFAULT_MAX_OUTPUT_TOKENS)
        } catch (e: AdapterModalityException) {
            throw AdapterException(
                AdapterErrorCategory.UNSUPPORTED_CAPABILITY,
                "this provider cannot accept the requested modality: ${e.modality}",
                cause = e,
            )
        } catch (e: AdapterSchemaException) {
            throw AdapterException(
                AdapterErrorCategory.INVALID_REQUEST,
                "tool input schema is not valid JSON",
                cause = e,
            )
        }

    // ThrowsCount: タイムアウト・分類済み例外の素通し・未知I/Oの3経路はどれも別の分類へ写す。
    // 1つにまとめると8分類の情報が失われる（15.1 Step1の要求そのものが果たせない）。
    @Suppress("ThrowsCount")
    private suspend fun callWithTimeout(
        request: AdapterRequest,
        path: String,
        body: String,
        stream: Boolean,
    ): HttpReply {
        val call = messagesCall(request, body, stream, path)
        return try {
            // transport側にもタイムアウトを渡しているが、SPI契約（timeout厳守）を
            // クライアント実装に依存させないため、ここでも二重に締める。
            withTimeout(request.timeout.toMillis()) { requireTransport().send(call) }
        } catch (e: TimeoutCancellationException) {
            throw AdapterException(
                AdapterErrorCategory.TRANSIENT,
                "provider call exceeded AdapterRequest.timeout of ${request.timeout}",
                cause = e,
            )
        } catch (e: AdapterException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // 種別を絞ると未知のI/O例外がそのままコアへ出て、8分類の外側になる（15.1 Step1違反）。
            throw ErrorMapper.toTransport(e, "execute")
        }
    }

    private fun messagesCall(
        request: AdapterRequest,
        body: String,
        stream: Boolean,
        path: String = MESSAGES_PATH,
    ): HttpCall =
        HttpCall(
            method = "POST",
            path = path,
            headers = headersForCurrentCredential(stream) + request.traceHeaders,
            body = body,
            timeout = request.timeout,
        )

    private fun ensureSupported(capabilityId: CapabilityId) {
        if (capabilityId !in SUPPORTED_CAPABILITIES) {
            throw AdapterException(
                AdapterErrorCategory.UNSUPPORTED_CAPABILITY,
                "capability is not supported by this adapter: ${capabilityId.value}",
            )
        }
    }

    private fun headersForCurrentCredential(sse: Boolean) = headersFor(currentCredentialRef(), sse)

    /**
     * 認証ヘッダを組み立てる。鍵は[SecretAccessor]から都度取り、[SecretValue.use]の
     * スコープ内でだけ文字列化する（フィールド保持もキャッシュもしない。不変条件4）。
     */
    private fun headersFor(
        ref: CredentialRef,
        stream: Boolean,
    ): Map<String, String> {
        val headers = mutableMapOf(API_VERSION_HEADER to API_VERSION)
        if (stream) headers[ACCEPT_HEADER] = SSE_CONTENT_TYPE
        val accessor = secrets ?: throw notInitialized()
        accessor.resolve(ref).use { secret ->
            headers[API_KEY_HEADER] = String(secret.charArray())
        }
        return headers
    }

    private inline fun resolveSecret(block: (CharArray) -> Unit) {
        val accessor = secrets ?: throw notInitialized()
        accessor.resolve(currentCredentialRef()).use { block(it.charArray()) }
    }

    private fun currentCredentialRef(): CredentialRef {
        val options = config?.options ?: throw notInitialized()
        return CredentialRef(
            secretRef = options[CREDENTIAL_REF_OPTION] ?: DEFAULT_CREDENTIAL_REF_NAME,
            version = options[CREDENTIAL_VERSION_OPTION]?.toIntOrNull() ?: 1,
            state = CredentialState.ACTIVE,
        )
    }

    private fun baseUrlOf(config: AdapterConfig): String =
        // Endpointは重み付きで複数あり得るが、実APIは単一のグローバルエンドポイント。
        // 重み最大のものを使う（region別振り分けはこのProviderでは不要）。
        config.endpoints
            .maxByOrNull { it.weight }
            ?.baseUrl
            ?.trimEnd('/') ?: DEFAULT_BASE_URL

    private fun requireTransport(): HttpTransport = transport ?: throw notInitialized()

    private fun notInitialized(): AdapterException =
        AdapterException(
            AdapterErrorCategory.PROVIDER_UNAVAILABLE,
            "adapter is not initialized (initialize() must be called before use)",
        )

    private fun malformedResponse(cause: Throwable): AdapterException =
        AdapterException(
            AdapterErrorCategory.PROVIDER_UNAVAILABLE,
            "provider returned a response that could not be parsed",
            cause = cause,
        )

    companion object {
        val CAPABILITY_CHAT = CapabilityId("chat")
        val CAPABILITY_STREAMING = CapabilityId("streaming")
        val CAPABILITY_TOOL_CALLING = CapabilityId("tool_calling")

        val SUPPORTED_CAPABILITIES = setOf(CAPABILITY_CHAT, CAPABILITY_STREAMING, CAPABILITY_TOOL_CALLING)

        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val MESSAGES_PATH = "/v1/messages"
        const val MODELS_PATH = "/v1/models"

        const val API_KEY_HEADER = "x-api-key"
        const val API_VERSION_HEADER = "anthropic-version"
        const val API_VERSION = "2023-06-01"
        const val ACCEPT_HEADER = "accept"
        const val SSE_CONTENT_TYPE = "text/event-stream"

        /** `AdapterConfig.options` で参照名を差し替えるためのキー（SPIにCredentialRefの受け口が無いための回避）。 */
        const val CREDENTIAL_REF_OPTION = "credential.ref"
        const val CREDENTIAL_VERSION_OPTION = "credential.version"
        const val DEFAULT_CREDENTIAL_REF_NAME = "anthropic-api-key"

        /**
         * 実APIは`max_tokens`が必須だがSPIの`GenerationParams.maxTokens`は任意。
         * Model側の上限もAdapterへは渡らないため、ここで既定値を持つほかない（findings参照）。
         */
        const val DEFAULT_MAX_OUTPUT_TOKENS = 4096
        const val DEFAULT_CONTEXT_WINDOW = 200_000

        private val SUCCESS_RANGE = 200..299
        private val AUTH_STATUSES = setOf(401, 403)
        private const val SERVER_ERROR_STATUS = 500
        private val VALIDATE_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val DISCOVERY_TIMEOUT: Duration = Duration.ofSeconds(15)
        private val HEALTH_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
