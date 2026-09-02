package apap.gateway

import apap.gateway.auth.JwksTokenVerifier
import apap.gateway.auth.TokenVerifier
import apap.gateway.config.AuthConfig
import apap.gateway.config.GatewayConfig
import apap.gateway.metrics.InMemoryCollectingReader
import apap.gateway.metrics.OpenMetricsRenderer
import apap.runtime.ApapEngine
import apap.runtime.ApapEngineBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.opentelemetry.sdk.metrics.SdkMeterProvider
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
    val server =
        embeddedServer(Netty, port = config.port) {
            apapGateway(engine, config, tokenVerifier, metricsRenderer)
        }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info("shutdown signal received; draining in-flight requests")
            server.stop(
                gracePeriodMillis = config.shutdown.gracePeriodSeconds * MILLIS_PER_SECOND,
                timeoutMillis = config.shutdown.streamingGraceSeconds * MILLIS_PER_SECOND,
            )
            engine.close()
            logger.info("shutdown complete")
        },
    )
    server.start(wait = true)
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
