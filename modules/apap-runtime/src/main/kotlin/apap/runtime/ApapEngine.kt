package apap.runtime

import apap.api.ApapHealth
import apap.api.ApapRequest
import apap.api.ApapResponse
import apap.api.ApapStreamChunk
import apap.api.CapabilityDescriptor
import apap.domain.model.vo.TenantId
import apap.domain.port.MetricsRecorder
import apap.domain.port.ScheduledTask
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
     * このエンジンが使っているメトリクス記録口（2.19）。
     *
     * 埋込ホストとGatewayが**自分の入口時刻**を`apap_overhead_duration_seconds`へ足せるように
     * 公開する（ADR-0034）。NFR-PRF-001の計測区間は「Gateway受信〜Adapter送信」であり、
     * その始点はAPAPの外側——HTTP層やホストのハンドラ——にあるため、
     * エンジン内部の計測点だけでは要件の区間を覆えない。
     *
     * 例（Gateway）: 受信時刻を控え、`engine.execute(...)`を呼ぶ直前に
     * `metrics.recordOverheadDuration("gateway", 経過秒)` を呼ぶ。
     */
    val metrics: MetricsRecorder

    /**
     * 周期実行すべきタスク（FR-EXE-006 / ADR-0032）。
     *
     * **APAPはこれらを自分では実行しない。** 埋込ライブラリが常駐スレッドを勝手に起こすと
     * 宿主のライフサイクル管理と衝突するため、駆動は宿主に委ねる。
     * 宿主が駆動しない場合、次が**動かないまま**になる:
     *
     * - Providerの定期健全性監視（FR-PRV-006）。`/health/providers`は初期値のままになり、
     *   Routingのヘルスフィルタも効かない
     * - Credential Rotationの期日到来検知（FR-SEC-002）
     * - 監査ログ・Conversation履歴の保持期間による削除（NFR-SEC-003 / NFR-DAT-001、いずれも未実装）
     *
     * 駆動例（宿主がコルーチンを持つ場合）:
     * ```
     * scheduledTasks.forEach { task ->
     *     scope.launch { while (isActive) { runCatching { task.runOnce() }; delay(task.interval.toMillis()) } }
     * }
     * ```
     * `gateway/apap-gateway` は常駐プロセスとして既定の駆動実装を持つ（`ScheduledTaskRunner`）。
     */
    val scheduledTasks: List<ScheduledTask>

    /**
     * DRAINING（新規[execute]/[executeStream]呼出を`IllegalStateException`で拒否）→
     * 実行中リクエストの完遂待ち（既定タイムアウトあり）→ Plugin unload（[ApapEngineBuilder.pluginDirectory]
     * を設定した場合のみ、`PluginManager.loadedPluginIds()`が返す全Pluginを`unload`）の順に行う。
     * 複数回の呼出は冪等（2回目以降は何もしない）。
     */
    override fun close()
}
