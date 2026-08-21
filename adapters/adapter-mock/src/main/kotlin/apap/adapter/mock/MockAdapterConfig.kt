package apap.adapter.mock

import apap.adapter.spi.AdapterChunk
import apap.adapter.spi.CapabilityId
import apap.adapter.spi.HealthResult
import apap.adapter.spi.ProviderHealthStatus
import apap.adapter.spi.TokenCount
import apap.adapter.spi.Usage
import java.time.Duration

/**
 * 15_Provider追加手順.md / 16_拡張ポイント.md 16.1: テスト用途の決定的Provider Adapter設定。
 * 個別呼出単位の挙動（特定のエラー分類を発生させる、タイムアウトを超過させる等）は
 * [MockAdapterHeaders] で説明する`AdapterRequest.traceHeaders`経由で指定する
 * （このConfigはAdapterインスタンス全体に効く既定値のみを持つ）。
 */
data class MockAdapterConfig(
    val supportedCapabilities: Set<CapabilityId> = setOf(CapabilityId("chat")),
    val latency: Duration = Duration.ZERO,
    val streamChunks: List<AdapterChunk> = emptyList(),
    val usage: Usage = Usage.of(TokenCount(DEFAULT_TOKEN_COUNT), TokenCount(DEFAULT_TOKEN_COUNT)),
    val healthResult: HealthResult = HealthResult(ProviderHealthStatus.UP, Duration.ZERO),
    val estimateTokensValue: TokenCount? = null,
) {
    init {
        require(!latency.isNegative) { "latency must not be negative: $latency" }
    }

    companion object {
        private const val DEFAULT_TOKEN_COUNT = 10
    }
}

/**
 * `AdapterRequest.traceHeaders`に設定することで、[MockProviderAdapter]の呼出単位の挙動を制御する
 * テスト専用の予約キー。実Providerには存在しない、このAdapter固有の裏口である。
 */
object MockAdapterHeaders {
    /** 値: `apap.domain.model.vo.AdapterErrorCategory`のenum名。設定するとそのカテゴリで[execute]が失敗する。 */
    const val FORCED_ERROR_CATEGORY = "apap.mock.forcedErrorCategory"

    /** 値: 追加の遅延（ミリ秒）。`AdapterRequest.timeout`超過を再現するために使う。 */
    const val EXTRA_DELAY_MILLIS = "apap.mock.extraDelayMillis"
}
