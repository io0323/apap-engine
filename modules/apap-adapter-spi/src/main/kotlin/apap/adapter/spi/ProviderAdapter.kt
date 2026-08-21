package apap.adapter.spi

import apap.domain.model.vo.CapabilityId
import apap.domain.model.vo.ContentPart
import apap.domain.model.vo.CredentialRef
import apap.domain.model.vo.Period
import apap.domain.model.vo.SemVer
import apap.domain.model.vo.TokenCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 03_基本設計.md 3.3.2（最重要契約） / 15_Provider追加手順.md / 16_拡張ポイント.md 16.1。
 * すべてのProvider Pluginが実装するSPI。Capability毎の対応可否は[supportedCapabilities]で申告し、
 * 未対応Capabilityでの[execute]呼出は[AdapterErrorCategory.UNSUPPORTED_CAPABILITY]で拒否すること
 * （15.1 Step1）。
 *
 * Kotlin化方針: I/Oを伴うメソッドは`suspend`とする。ライフサイクル系（[initialize]/[shutdown]/
 * [spiVersion]/[supportedCapabilities]/[capabilityConstraints]/[translateTools]）はI/Oを伴わない
 * 同期メソッドのまま維持する。
 *
 * 03_基本設計.md 3.3.2で定義された固定のSPI契約であり、メソッド数は設計書通り（分割はSPIの分裂を招くため
 * `TooManyFunctions`を抑制する）。
 */
@Suppress("TooManyFunctions")
interface ProviderAdapter {
    // --- ライフサイクル ---------------------------------------------------------------------
    fun initialize(
        config: AdapterConfig,
        secrets: SecretAccessor,
    )

    fun shutdown()

    fun spiVersion(): SemVer

    // --- 能力申告 ---------------------------------------------------------------------------
    fun supportedCapabilities(): Set<CapabilityId>

    fun capabilityConstraints(capabilityId: CapabilityId): CapabilityConstraints

    // --- 認証（抽象化。実装内でapi_key/oauth2/署名等を処理） --------------------------------
    suspend fun authenticate(): AuthContext

    suspend fun validateCredential(ref: CredentialRef): ValidationResult

    // --- 実行（同期） -----------------------------------------------------------------------

    /** @throws AdapterException 分類済みエラー（TRANSIENT|RATE_LIMITED|AUTH_ERROR|...）。 */
    suspend fun execute(request: AdapterRequest): AdapterResponse

    // --- 実行（ストリーム）: pull型。next()がブロック/待機 -----------------------------------
    suspend fun executeStream(request: AdapterRequest): AdapterStream

    interface AdapterStream {
        /** @return 次のチャンク。nullは終端（正常終了）。 */
        suspend fun next(): AdapterChunk?

        /** Provider接続を確実に切断する。ストリーム消費側が途中終了する場合に必ず呼ぶこと。 */
        fun cancel()
    }

    // --- Tool/Function変換（共通形式 ⇄ Provider固有形式） -----------------------------------
    fun translateTools(tools: List<ToolDefinition>): ProviderToolFormat

    // --- 管理系 -----------------------------------------------------------------------------
    suspend fun discoverModels(): List<DiscoveredModel>

    suspend fun healthCheck(): HealthResult

    /** Provider側集計API未提供のAdapterはnullを返す（任意対応）。 */
    suspend fun fetchUsage(period: Period): ProviderUsage?

    /** Provider側請求API未提供のAdapterはnullを返す（任意対応）。 */
    suspend fun fetchCost(period: Period): ProviderCost?

    /**
     * ADR-0010: オプショナルメソッド。正確なトークナイザーを提供できる場合のみ実装する。
     * nullの場合はコア側が`HEURISTIC`モードで推定し安全マージン15%を適用する（ADR-0009）。
     * 未実装Adapterは本メソッドをoverrideする必要がない（デフォルト実装がnullを返す）。
     */
    suspend fun estimateTokens(input: List<ContentPart>): TokenCount? = null
}

/**
 * [ProviderAdapter.AdapterStream] をsuspendによる自然なバックプレッシャを保ったまま`Flow`へ変換する。
 * 正常終了（`next()`がnullを返す）以外の経路でコレクションが終わる場合（下流のキャンセル・例外）は、
 * 02_システム仕様.md「クライアント切断時はProviderへcancel()を伝播する」に従い[ProviderAdapter.AdapterStream.cancel]を呼ぶ。
 */
fun ProviderAdapter.AdapterStream.asFlow(): Flow<AdapterChunk> =
    flow {
        var completedNormally = false
        try {
            while (true) {
                val chunk = next() ?: break
                emit(chunk)
            }
            completedNormally = true
        } finally {
            if (!completedNormally) cancel()
        }
    }
