package apap.adapter.mock

import apap.adapter.spi.AdapterChunk
import apap.adapter.spi.AdapterErrorCategory
import apap.adapter.spi.CapabilityId
import apap.adapter.spi.HealthResult
import apap.adapter.spi.ProviderHealthStatus
import apap.adapter.spi.TokenCount
import apap.adapter.spi.Usage
import java.time.Duration

/**
 * 15_Provider追加手順.md / 16_拡張ポイント.md 16.1: テスト用途の決定的Provider Adapter設定。
 * [forcedErrorCategory] / [extraDelayMillis] は個別呼出単位の挙動（特定のエラー分類を発生させる、
 * タイムアウトを超過させる等）を制御するテスト専用フィールドである。実Providerの設定には存在しない、
 * このAdapter固有の裏口であり、`AdapterRequest.traceHeaders`（W3C Trace Context伝播用の汎用SPIフィールド）
 * には相乗りさせない（着手前の修正: MockProviderAdapterの挙動制御をtrace-headersから専用のテスト用
 * フィールドへ移す）。
 */
data class MockAdapterConfig(
    val supportedCapabilities: Set<CapabilityId> = setOf(CapabilityId("chat")),
    val latency: Duration = Duration.ZERO,
    val streamChunks: List<AdapterChunk> = emptyList(),
    val usage: Usage = Usage.of(TokenCount(DEFAULT_TOKEN_COUNT), TokenCount(DEFAULT_TOKEN_COUNT)),
    val healthResult: HealthResult = HealthResult(ProviderHealthStatus.UP, Duration.ZERO),
    val estimateTokensValue: TokenCount? = null,
    /** 設定すると[MockProviderAdapter.execute]/[MockProviderAdapter.executeStream]がこのカテゴリで失敗する。 */
    val forcedErrorCategory: AdapterErrorCategory? = null,
    /** [MockProviderAdapter.execute]に追加する遅延（ミリ秒）。`AdapterRequest.timeout`超過の再現に使う。 */
    val extraDelayMillis: Long = 0,
) {
    init {
        require(!latency.isNegative) { "latency must not be negative: $latency" }
        require(extraDelayMillis >= 0) { "extraDelayMillis must not be negative: $extraDelayMillis" }
    }

    companion object {
        private const val DEFAULT_TOKEN_COUNT = 10
    }
}
