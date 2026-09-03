package apap.gateway

import apap.domain.port.ScheduledTask
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * ADR-0032: 常駐プロセスであるGatewayが、[ScheduledTask] の**既定の駆動実装**を持つ。
 *
 * これはビジネスロジックではなく「ホストとしての起動責務」である。何を実行するかは知らず、
 * `ApapEngine.scheduledTasks` を各自の間隔で回すだけなので、
 * 「Gatewayにビジネスロジックを置かない」制約には抵触しない。
 *
 * ## 設計上の注意
 *
 * - **1タスクの失敗が他を止めない**: [SupervisorJob] と各ループ内の`runCatching`で二重に守る。
 *   Providerが1つ落ちているだけで全ての周期処理が止まるのは、監視として最悪の壊れ方になる。
 * - **停止できること**: [close] でスコープごとキャンセルする。Gatewayの停止シーケンス
 *   （`shutdownGateway`）から呼ばれ、プロセスにコルーチンを残さない。
 * - **起動直後には走らせない**: 最初の実行を1周期ぶん遅らせる。起動時はProvider登録や
 *   Plugin初期化が終わっていない可能性があり、そこで健全性を測ると誤ってDOWNと判定しうる。
 */
class ScheduledTaskRunner(
    private val tasks: List<ScheduledTask>,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("apap-scheduled-tasks"))
    private val jobs = mutableListOf<Job>()

    /** 各タスクを自分の間隔で回し始める。 */
    fun start() {
        tasks.forEach { task ->
            jobs +=
                scope.launch {
                    while (isActive) {
                        delay(task.interval.toMillis())
                        runCatching { task.runOnce() }
                            .onFailure { logger.warn("scheduled task {} failed: {}", task.name, it.message, it) }
                    }
                }
        }
        logger.info("started {} scheduled task(s): {}", tasks.size, tasks.joinToString { it.name })
    }

    override fun close() {
        scope.cancel()
        jobs.clear()
    }

    private companion object {
        val logger = LoggerFactory.getLogger(ScheduledTaskRunner::class.java)
    }
}
