package apap.gateway

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 実行中リクエスト数と排出（DRAINING）状態を持つ。
 *
 * **存在理由**: Ktor/Nettyの`server.stop(gracePeriodMillis, timeoutMillis)`は
 * in-flightリクエストの完遂を保証しない。gracePeriodはNettyの`shutdownGracefully`の
 * quiet periodへ渡り、「その間に新しいタスクが来なければ落とす」という意味になる。
 * 本Gatewayのリクエストはほぼ全時間をProvider応答待ちで**サスペンド**して過ごすため、
 * イベントループから見ると「静か」で、処理中でも落とされてしまう。
 *
 * 実測（`GracefulShutdownTest`）でもこれを確認した——`server.stop`だけに任せると、
 * 実行中のリクエストはレスポンスを返さずに接続を切られる
 * （`ClosedReadChannelException: the server prematurely closed the connection`）。
 *
 * したがってGateway自身がin-flightを数え、0になるまで待ってから`stop`する。
 */
class GatewayLifecycle {
    private val inFlight = AtomicInteger(0)
    private val draining = AtomicBoolean(false)

    val isDraining: Boolean get() = draining.get()
    val inFlightCount: Int get() = inFlight.get()

    fun requestStarted() {
        inFlight.incrementAndGet()
    }

    fun requestFinished() {
        inFlight.decrementAndGet()
    }

    /** 排出開始。以後`/readyz`は503を返し、ロードバランサから外れる。 */
    fun beginDraining() {
        draining.set(true)
    }

    /**
     * in-flightが0になるまで待つ。[timeoutMillis]を超えたら諦めて`false`を返す
     * （呼び出し側は「まだ残っている」ことを知ったうえで停止を進められる——
     * 黙って成功扱いにすると、切られたリクエストの存在が見えなくなる）。
     */
    suspend fun awaitQuiescence(timeoutMillis: Long): Boolean {
        val drained =
            withTimeoutOrNull(timeoutMillis) {
                while (inFlight.get() > 0) {
                    delay(POLL_INTERVAL_MILLIS)
                }
                true
            }
        if (drained == null) {
            logger.warn("drain timed out with {} request(s) still in flight", inFlight.get())
        }
        return drained ?: false
    }

    private companion object {
        val logger = LoggerFactory.getLogger(GatewayLifecycle::class.java)
        const val POLL_INTERVAL_MILLIS = 20L
    }
}
