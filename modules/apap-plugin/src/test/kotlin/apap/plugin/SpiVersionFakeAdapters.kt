package apap.plugin

import apap.adapter.spi.AdapterConfig
import apap.adapter.spi.AdapterRequest
import apap.adapter.spi.AdapterResponse
import apap.adapter.spi.AuthContext
import apap.adapter.spi.CapabilityId
import apap.adapter.spi.CredentialRef
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
import apap.adapter.spi.SpiSurface
import apap.adapter.spi.ToolDefinition
import apap.adapter.spi.ValidationResult
import java.time.Duration

/**
 * **申告するSPIバージョンだけ**が異なるAdapter群（ADR-0043のロード時検証用）。
 *
 * `adapter-mock`の実jarは`SpiSurface.version`をそのまま返すため、
 * 「マニフェストの記載とコードの申告が食い違う」状況を作れない。ここでは`ServiceLoader`が
 * 拾えるだけの最小のPluginを組み立て、申告値を固定した実装を差し込む。
 *
 * これらは分離ClassLoaderの検証には使わない（テストのクラスパス上にあるため親から解決される）。
 * 分離の検証は`adapter-mock`の実jarを使う既存のテストが担う。
 */
abstract class FixedSpiVersionAdapter(
    private val declaredSpiVersion: SemVer,
) : ProviderAdapter {
    override fun spiVersion(): SemVer = declaredSpiVersion

    override fun initialize(
        config: AdapterConfig,
        secrets: SecretAccessor,
    ) = Unit

    override fun shutdown() = Unit

    override fun supportedCapabilities(): Set<CapabilityId> = setOf(CapabilityId("chat"))

    override suspend fun authenticate(): AuthContext = AuthContext()

    override suspend fun validateCredential(ref: CredentialRef): ValidationResult = ValidationResult(valid = true)

    override suspend fun execute(request: AdapterRequest): AdapterResponse = unused()

    override suspend fun executeStream(request: AdapterRequest): ProviderAdapter.AdapterStream = unused()

    override fun translateTools(tools: List<ToolDefinition>): ProviderToolFormat = ProviderToolFormat(emptyList<Any>())

    override suspend fun discoverModels(): List<DiscoveredModel> = emptyList()

    override suspend fun healthCheck(): HealthResult = HealthResult(ProviderHealthStatus.UP, Duration.ZERO)

    override suspend fun fetchUsage(period: Period): ProviderUsage? = null

    override suspend fun fetchCost(period: Period): ProviderCost? = null

    private fun unused(): Nothing = throw UnsupportedOperationException("this fake only declares an SPI version")
}

/** ホストより古いSPIでビルドされたAdapter（マニフェストが新しいレンジを騙るケースに使う）。 */
class AdapterBuiltAgainstOlderSpi : FixedSpiVersionAdapter(SemVer(1, 1, 0))

/**
 * ホストと同じメジャーだが**新しいマイナー**でビルドされたAdapter。
 * メジャー一致だけを見る互換判定（[SemVer.isCompatibleWith]）では通ってしまい、
 * 実行時にホストへ存在しないSPIメンバを参照して落ちる。
 */
class AdapterBuiltAgainstNewerSpi : FixedSpiVersionAdapter(oneMinorAheadOfHost())

private fun oneMinorAheadOfHost(): SemVer = SemVer(SpiSurface.version.major, SpiSurface.version.minor + 1, 0)

/** ホストと一致するAdapter（正常系）。 */
class AdapterMatchingHostSpi : FixedSpiVersionAdapter(SpiSurface.version)
