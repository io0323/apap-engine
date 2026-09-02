package apap.runtime

import apap.domain.port.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText

/**
 * 03_基本設計.md 3.15の`application.yaml`スキーマ（[ApapConfig]）を、ファイル/Map/
 * プログラマティックの3経路で構築でき、かつ[ApapEngineBuilder.applyConfig]で実際にSPIバインドへ
 * 反映されることの検証。
 */
class ApapConfigTest {
    @Test
    fun `programmatic construction keeps the given values`() {
        val config = ApapConfig(routingStrategy = "weighted-score", pluginDir = "/opt/apap/plugins")

        assertEquals("weighted-score", config.routingStrategy)
        assertEquals("/opt/apap/plugins", config.pluginDir)
        assertNull(config.cacheStore)
        assertTrue(config.pluginSignatureRequired, "signature verification must default to required")
    }

    @Test
    fun `fromMap reads the 3-15 dotted keys`() {
        val config =
            ApapConfig.fromMap(
                mapOf(
                    "routing.strategy" to "weighted-score",
                    "retry.strategy" to "exp-backoff-jitter",
                    "cache.store" to "in-memory",
                    "secret.store" to "env-var",
                    "compaction.strategy" to "truncate-oldest",
                    "plugin.dir" to "/opt/apap/plugins",
                    "plugin.signature.required" to "true",
                ),
            )

        assertEquals("weighted-score", config.routingStrategy)
        assertEquals("exp-backoff-jitter", config.retryStrategy)
        assertEquals("in-memory", config.cacheStore)
        assertEquals("env-var", config.secretStore)
        assertEquals("truncate-oldest", config.compactionStrategy)
        assertEquals("/opt/apap/plugins", config.pluginDir)
        assertTrue(config.pluginSignatureRequired)
    }

    @Test
    fun `fromYamlFile reads the 3-15 application yaml shape, ignoring comments and blank lines`(
        @TempDir tempDir: Path,
    ) {
        val file = tempDir.resolve("application.yaml")
        file.writeText(
            """
            # 先頭コメントは無視される
            apap:
              routing.strategy: weighted-score
              # 途中のコメントも無視される

              retry.strategy: exp-backoff-jitter
              compaction.strategy: truncate-oldest
              plugin.signature.required: true
            """.trimIndent(),
        )

        val config = ApapConfig.fromYamlFile(file)

        assertEquals("weighted-score", config.routingStrategy)
        assertEquals("exp-backoff-jitter", config.retryStrategy)
        assertEquals("truncate-oldest", config.compactionStrategy)
        assertNull(config.cacheStore, "未指定のキーはnullのまま（既定値の決定はApapEngineBuilder側の責務）")
    }

    @Test
    fun `applyConfig binds the named built-in implementations and builds an engine`() {
        val config =
            ApapConfig.fromMap(
                mapOf(
                    "routing.strategy" to "weighted-score",
                    "retry.strategy" to "exp-backoff-jitter",
                    "cache.store" to "in-memory",
                    "secret.store" to "env-var",
                    "compaction.strategy" to "truncate-oldest",
                ),
            )

        // 名前解決が全て成功し、engineが組み上がることをもって束縛が成立したとみなす
        // （個々のStrategyが実際に呼ばれることはApapEngineBuilderTestのSPI差替テストが検証する）。
        val engine =
            ApapEngineBuilder()
                .applyConfig(config)
                .build()
        engine.close()
    }

    @Test
    fun `applyConfig can be called before or after clock, so cache-store binding does not capture a stale clock`() {
        val fixedClock =
            object : Clock {
                override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
            }
        val config = ApapConfig(cacheStore = "in-memory")

        // `cache.store: in-memory`の実体生成はbuild()へ委ねられるため、applyConfigとclockの
        // 呼出順に関わらずbuildできる（順序依存で古いClockを掴む退行の検出が目的。
        // どちらのClockを掴んだかまでは外から観測できないため、ここでは順序非依存性のみを担保する）。
        ApapEngineBuilder()
            .applyConfig(config)
            .clock(fixedClock)
            .build()
            .close()
        ApapEngineBuilder()
            .clock(fixedClock)
            .applyConfig(config)
            .build()
            .close()
    }

    @Test
    fun `applyConfig rejects an unknown implementation name instead of silently falling back`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                ApapEngineBuilder().applyConfig(ApapConfig(routingStrategy = "no-such-strategy"))
            }

        assertTrue(error.message!!.contains("no-such-strategy"), "the offending value must appear in the message")
        assertTrue(error.message!!.contains("weighted-score"), "the known names must be listed to guide the fix")
    }

    @Test
    fun `applyConfig rejects names that need external connection details rather than guessing`() {
        // 3.15の例示値。接続情報を名前だけで決められないため、実装インスタンスの直接注入を促す。
        assertThrows(IllegalArgumentException::class.java) {
            ApapEngineBuilder().applyConfig(ApapConfig(cacheStore = "distributed-kvs"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ApapEngineBuilder().applyConfig(ApapConfig(secretStore = "vault-compatible"))
        }
    }

    @Test
    fun `applyConfig refuses to disable plugin signature verification`() {
        assertThrows(IllegalArgumentException::class.java) {
            ApapEngineBuilder().applyConfig(ApapConfig(pluginSignatureRequired = false))
        }
    }

    @Test
    fun `a plugin directory without a trusted public key fails the build instead of degrading silently`(
        @TempDir tempDir: Path,
    ) {
        val builder = ApapEngineBuilder().applyConfig(ApapConfig(pluginDir = tempDir.toString()))

        val error = assertThrows(IllegalArgumentException::class.java) { builder.build() }

        assertTrue(
            error.message!!.contains("trusted public key"),
            "the failure must say the signing key is missing, not silently resolve to an empty registry",
        )
    }
}
