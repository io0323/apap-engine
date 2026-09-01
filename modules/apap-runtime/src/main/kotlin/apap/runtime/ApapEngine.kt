package apap.runtime

import apap.api.ApapHealth
import apap.api.ApapRequest
import apap.api.ApapResponse
import apap.api.ApapStreamChunk
import apap.api.CapabilityDescriptor
import apap.domain.model.vo.TenantId
import kotlinx.coroutines.flow.Flow

/**
 * 03_基本設計.md 3.10 `ApapFacade`（CLAUDE.md名前空間対応表: `ApapEngine`）。SDKが利用する単一入口。
 * 生成は必ず[ApapEngineBuilder]を経由する（コンポジションルート、CLAUDE.md不変条件6）。
 *
 * 3.10は`chat(req)/completion(req)/embed(req)/image(req)/audio(req)`とCapability別のメソッドを
 * 挙げるが、`apap.execution.ExecutionEngine`が既に`CanonicalRequest.capabilityId`によるCapability
 * 横断の単一エントリで実装されているため、それに合わせ[execute]/[executeStream]へ統合する
 * （要件充足に影響しない実装判断のためADR化しない。既存実装との整合を優先した）。
 */
interface ApapEngine : AutoCloseable {
    suspend fun execute(request: ApapRequest): ApapResponse

    fun executeStream(request: ApapRequest): Flow<ApapStreamChunk>

    suspend fun capabilities(tenantId: TenantId): List<CapabilityDescriptor>

    val admin: ApapAdmin

    val health: ApapHealth

    /**
     * DRAINING（新規[execute]/[executeStream]呼出を`IllegalStateException`で拒否）→
     * 実行中リクエストの完遂待ち（既定タイムアウトあり）→ Plugin unload（[ApapEngineBuilder.pluginDirectory]
     * を設定した場合のみ、`PluginManager.loadedPluginIds()`が返す全Pluginを`unload`）の順に行う。
     * 複数回の呼出は冪等（2回目以降は何もしない）。
     */
    override fun close()
}
