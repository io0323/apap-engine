package apap.gateway

import apap.gateway.auth.JwksTokenVerifier
import apap.gateway.auth.TokenVerifier
import apap.gateway.config.AuthConfig
import apap.gateway.config.GatewayConfig
import apap.gateway.metrics.InMemoryCollectingReader
import apap.gateway.metrics.OpenMetricsRenderer
import apap.runtime.ApapEngine
import apap.runtime.ApapEngineBuilder
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("apap.gateway.Main")

fun main() {
    val config =
        GatewayConfig(
            port = envInt("APAP_GATEWAY_PORT", GatewayConfig.DEFAULT_PORT),
            auth = authConfigFromEnv(),
        )
    val metricsReader = InMemoryCollectingReader()
    val meterProvider = SdkMeterProvider.builder().registerMetricReader(metricsReader).build()

    val engine: ApapEngine =
        ApapEngineBuilder()
            .meter(meterProvider.get("apap-gateway"))
            .build()

    startGateway(engine, config, JwksTokenVerifier(config.auth), OpenMetricsRenderer(metricsReader))
}

/**
 * サーバを起動し、シャットダウンフックでグレースフルに停止する（本タスク指示9）。
 *
 * 停止順序が重要:
 * 1. Ktorの`stop(gracePeriod, timeout)`が新規受付を止め、in-flightの完遂を待つ。
 *    Streamingは長時間続くため、timeoutに[apap.gateway.config.ShutdownConfig.streamingGraceSeconds]
 *    （既定300s = 2.10の全体タイムアウト既定）を与える。
 * 2. その後に[ApapEngine.close]でエンジンをDRAININGにし、Plugin unloadまで行う。
 *
 * 逆順（先にengine.close）にすると、まだ処理中のHTTPリクエストが
 * 「新規リクエスト扱いで拒否される」ため、in-flight完遂の要件を満たせない。
 */
fun startGateway(
    engine: ApapEngine,
    config: GatewayConfig,
    tokenVerifier: TokenVerifier,
    metricsRenderer: OpenMetricsRenderer,
) {
    val lifecycle = GatewayLifecycle()
    val server =
        embeddedServer(Netty, port = config.port) {
            apapGateway(engine, config, tokenVerifier, metricsRenderer, lifecycle = lifecycle)
        }
    // ADR-0032: 周期タスクの駆動主体。APAPは自分でスレッドを起こさないので、
    // 常駐プロセスであるGatewayが回す。これが無いとProviderの健全性監視は動かない。
    val scheduledTasks = ScheduledTaskRunner(engine.scheduledTasks).apply { start() }
    Runtime.getRuntime().addShutdownHook(
        Thread { runBlocking { shutdownGateway(server, engine, config, lifecycle, scheduledTasks) } },
    )
    server.start(wait = true)
}

/**
 * グレースフルシャットダウン（本タスク指示9 / 11_デプロイメント図.md）。
 *
 * 順序に意味がある:
 * 1. **排出開始**——`/readyz`が503を返し、Kubernetesが新規トラフィックを止める。
 * 2. **in-flightの完遂を待つ**。`server.stop`のgracePeriodには任せられない:
 *    それはNettyの`shutdownGracefully`のquiet periodであり、Provider応答待ちで
 *    サスペンドしているリクエストは「静か」と見なされて接続ごと切られる
 *    （`GracefulShutdownTest`で実測済み。[GatewayLifecycle]のKDoc参照）。
 *    Streamingは長時間続くため、猶予は2.10の全体既定300秒を上限とする。
 * 3. **サーバ停止**。この時点で実行中のリクエストは無い。
 * 4. **エンジンclose**（DRAINING→Plugin unload）。
 *
 * 3と4を逆にしてはならない。先に`engine.close()`すると、まだ処理中のHTTPリクエストが
 * 「新規」として`IllegalStateException`で弾かれ、完遂させる目的を自ら壊す。
 */
suspend fun shutdownGateway(
    server: EmbeddedServer<*, *>,
    engine: ApapEngine,
    config: GatewayConfig,
    lifecycle: GatewayLifecycle,
    scheduledTasks: AutoCloseable? = null,
) {
    logger.info("shutdown signal received; draining in-flight requests")
    lifecycle.beginDraining()
    // 周期タスクを先に止める。排出中に新しい健全性チェックが走ると、
    // 「停止処理が終わらない」ではなく「停止中に新しい仕事が増える」形の遅延になる。
    scheduledTasks?.close()

    val drained = lifecycle.awaitQuiescence(config.shutdown.streamingGraceSeconds * MILLIS_PER_SECOND)
    if (!drained) {
        logger.warn("proceeding to stop with requests still in flight; they will be cut off")
    }

    server.stop(
        gracePeriodMillis = config.shutdown.gracePeriodSeconds * MILLIS_PER_SECOND,
        timeoutMillis = config.shutdown.streamingGraceSeconds * MILLIS_PER_SECOND,
    )
    engine.close()
    logger.info("shutdown complete")
}

/**
 * ADR-0004の`apap.auth`設定を環境変数から読む。
 *
 * **未設定なら起動を拒否する**（認証を無効化して起動する縮退動作を持たせない——
 * 設定漏れがそのまま無認証公開になるため）。
 */
private fun authConfigFromEnv(): AuthConfig =
    AuthConfig(
        issuer = requiredEnv("APAP_AUTH_ISSUER"),
        audience = requiredEnv("APAP_AUTH_AUDIENCE"),
        jwksUri = requiredEnv("APAP_AUTH_JWKS_URI"),
    )

private fun requiredEnv(name: String): String =
    System.getenv(name)
        ?: error(
            "$name is not set. The gateway refuses to start without authentication configuration " +
                "(ADR-0004); set it, or inject a TokenVerifier explicitly when embedding.",
        )

private fun envInt(
    name: String,
    default: Int,
): Int = System.getenv(name)?.toIntOrNull() ?: default

private const val MILLIS_PER_SECOND = 1000L
