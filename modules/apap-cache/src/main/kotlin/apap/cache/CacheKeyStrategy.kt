package apap.cache

import apap.domain.model.execution.CanonicalRequest
import apap.domain.model.execution.GenerationParams
import apap.domain.model.execution.ToolDefinition
import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.Money
import apap.domain.model.vo.RoutingConstraints
import apap.domain.model.vo.TenantId
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.TreeMap

/**
 * [CacheKeyStrategy.responseCacheKey]が返すキーの先頭prefixを一箇所で定義する。
 * Alias切替時の一括無効化（[DefaultCacheEngine]の`AliasChanged`購読）は、個々のキーの内容を
 * 知らずに`CacheStore.scanByPrefix(aliasPrefix(aliasId))`だけで対象を特定する必要があるため、
 * [CacheKeyStrategy]の実装は必ずこのprefixで始まるキーを返すこと（SPI差替時の契約）。
 */
object ResponseCacheKeys {
    const val NO_ALIAS_SENTINEL = "none"

    /** [resolvedAliasId]は`request.modelAlias`解決結果（未解決/無指定は`null`）。 */
    fun aliasPrefix(resolvedAliasId: String?): String = "resp:${resolvedAliasId ?: NO_ALIAS_SENTINEL}:"
}

/**
 * 16_拡張ポイント.md 16.4 SPI: キャッシュキー生成戦略。Request Cache（冪等キーそのもの）と
 * Response Cache（要求内容の正規化ハッシュ）でキー空間を分離する。
 */
interface CacheKeyStrategy {
    fun requestCacheKey(
        tenantId: TenantId,
        idempotencyKey: String,
    ): String

    /**
     * [resolvedAliasId]は`request.modelAlias`をAliasRepositoryで解決した結果（[DefaultCacheEngine]参照、
     * 無指定または未登録なら`null`）。戻り値は必ず[ResponseCacheKeys.aliasPrefix]で始まること。
     */
    fun responseCacheKey(
        tenantId: TenantId,
        capabilityId: CapabilityId,
        resolvedAliasId: String?,
        request: CanonicalRequest,
    ): String
}

/**
 * 02_システム仕様.md 2.14: 「JSONキー順序・空白の差異でキーが変化しない」正規化ハッシュ戦略。
 * JSONの正規化（キー順序の安定化）を自前実装せず[ObjectMapper]（[java.util.TreeMap]で構築した
 * 木をシリアライズすると、Jacksonはキーの自然順序を保ってそのまま出力する）へ委譲する
 * （`apap-provider/CapabilityRegistry`のjson-schema-validator委譲と同じ判断、ADR化せずここに根拠を残す）。
 *
 * ハッシュ対象は要求の決定性に関わるフィールドのみ（`input`/`params`/`tools`/`outputSchema`/
 * `constraints`/`preferences`）。`requestId`/`traceId`/`timeoutBudget`/`idempotencyKey`/
 * `sessionId`/`conversationId`/`principal`のような呼出ごとに変わる/認可に関わるフィールドは
 * 意図的にハッシュへ含めない。
 */
class NormalizedJsonCacheKeyStrategy : CacheKeyStrategy {
    private val objectMapper = ObjectMapper()

    override fun requestCacheKey(
        tenantId: TenantId,
        idempotencyKey: String,
    ): String = "req:${tenantId.value}:$idempotencyKey"

    override fun responseCacheKey(
        tenantId: TenantId,
        capabilityId: CapabilityId,
        resolvedAliasId: String?,
        request: CanonicalRequest,
    ): String {
        val contentHash = sha256Hex(objectMapper.writeValueAsString(canonicalStructure(request)))
        return "${ResponseCacheKeys.aliasPrefix(resolvedAliasId)}${tenantId.value}:${capabilityId.value}:$contentHash"
    }

    private fun canonicalStructure(request: CanonicalRequest): TreeMap<String, Any?> {
        val root = TreeMap<String, Any?>()
        root["input"] = request.input.map(::canonicalContentPart)
        root["params"] = canonicalParams(request.params)
        root["tools"] = request.tools?.map(::canonicalTool)
        root["outputSchema"] = request.outputSchema
        root["constraints"] = canonicalConstraints(request.constraints)
        root["preferences"] = TreeMap<String, Any?>().apply { put("optimizeFor", request.preferences.optimizeFor.name) }
        return root
    }

    private fun canonicalContentPart(part: ContentPart): TreeMap<String, Any?> {
        val map = TreeMap<String, Any?>()
        when (part) {
            is ContentPart.Text -> {
                map["type"] = "text"
                map["text"] = part.text
            }
            is ContentPart.Image -> {
                map["type"] = "image"
                map["uri"] = part.uri
                map["mimeType"] = part.mimeType
            }
            is ContentPart.Audio -> {
                map["type"] = "audio"
                map["uri"] = part.uri
                map["mimeType"] = part.mimeType
            }
            is ContentPart.Video -> {
                map["type"] = "video"
                map["uri"] = part.uri
                map["mimeType"] = part.mimeType
            }
            is ContentPart.Json -> {
                map["type"] = "json"
                map["json"] = part.json
            }
        }
        return map
    }

    private fun canonicalParams(params: GenerationParams): TreeMap<String, Any?> =
        TreeMap<String, Any?>().apply {
            put("temperature", params.temperature)
            put("maxTokens", params.maxTokens)
            put("topP", params.topP)
            put("stop", params.stop)
            put("seed", params.seed)
        }

    private fun canonicalTool(tool: ToolDefinition): TreeMap<String, Any?> =
        TreeMap<String, Any?>().apply {
            put("name", tool.name)
            put("description", tool.description)
            put("parametersSchema", tool.parametersSchema)
        }

    private fun canonicalConstraints(constraints: RoutingConstraints): TreeMap<String, Any?> =
        TreeMap<String, Any?>().apply {
            put("region", constraints.region?.code)
            put("maxCost", constraints.maxCost?.let(::canonicalMoney))
            put("maxLatencyMs", constraints.maxLatencyMs)
            put("excludeProviders", constraints.excludeProviders.map { it.value }.sorted())
        }

    private fun canonicalMoney(money: Money): TreeMap<String, Any?> =
        TreeMap<String, Any?>().apply {
            put("amount", money.amount.stripTrailingZeros().toPlainString())
            put("currency", money.currency)
        }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }
}
