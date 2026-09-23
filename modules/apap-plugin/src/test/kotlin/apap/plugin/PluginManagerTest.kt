package apap.plugin

import apap.adapter.spi.SpiSurface
import apap.domain.event.PluginLoaded
import apap.domain.event.PluginQuarantined
import apap.domain.event.PluginUnloaded
import apap.domain.model.plugin.PluginRegistrationStatus
import apap.domain.model.vo.SemVer
import apap.testkit.inmemory.InMemoryClock
import apap.testkit.inmemory.InMemoryDomainEventPublisher
import apap.testkit.inmemory.InMemoryIdGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.streams.asSequence

/**
 * `adapters:adapter-mock`の実jar（テスト実行時に`apap.plugin.test.adapterMockJarPath`システム
 * プロパティ経由で渡される、`build.gradle.kts`参照）を使い、分離URLClassLoaderでの実ロードを検証する。
 */
class PluginManagerTest {
    private lateinit var publicKey: PublicKey
    private lateinit var privateKey: PrivateKey
    private lateinit var adapterMockJarBytes: ByteArray
    private val ids = InMemoryIdGenerator()
    private val clock = InMemoryClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val eventPublisher = InMemoryDomainEventPublisher()
    private val events get() = eventPublisher.publishedEvents

    @BeforeEach
    fun setUp() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        publicKey = keyPair.public
        privateKey = keyPair.private
        adapterMockJarBytes = Files.readAllBytes(findAdapterMockJar())
    }

    private fun sign(bytes: ByteArray): String {
        val signature =
            Signature
                .getInstance("SHA256withRSA")
                .apply {
                    initSign(privateKey)
                    update(bytes)
                }.sign()
        return Base64.getEncoder().encodeToString(signature)
    }

    /**
     * Pluginのjarと、その中のサービス宣言が指すエントリポイント。
     * jarを差し替えるときはエントリポイントも必ず一緒に変わるため、1つの引数にまとめる。
     */
    private class PluginJar(
        val bytes: ByteArray,
        val entryPoint: String,
    )

    private fun mockJar() = PluginJar(adapterMockJarBytes, "apap.adapter.mock.MockProviderAdapter")

    private fun writePlugin(
        pluginsDir: Path,
        pluginId: String,
        spiVersionRange: String,
        signature: String,
        jar: PluginJar = mockJar(),
    ) {
        val dir = pluginsDir.resolve(pluginId).createDirectories()
        dir.resolve("plugin.yaml").writeText(
            """
            plugin_id: $pluginId
            version: 1.0.0
            spi_version: "$spiVersionRange"
            entry_point: ${jar.entryPoint}
            capabilities: [chat]
            auth_types: [api_key]
            signature: $signature
            """.trimIndent(),
        )
        dir.resolve("plugin.jar").writeBytes(jar.bytes)
    }

    private fun manager(currentSpiVersion: SemVer = SpiSurface.version) =
        PluginManager(eventPublisher, ids, clock, PluginSignatureVerifier(publicKey), currentSpiVersion)

    /**
     * `ServiceLoader`が[entryPoint]を拾えるだけの最小のjar。中身はサービス宣言1件のみで、
     * 実装クラス自体はテストのクラスパス（＝分離ClassLoaderの親）から解決される。
     * 申告SPIバージョンだけを変えたPluginを作るために使う。
     */
    private fun serviceOnlyJarFor(entryPoint: String): PluginJar {
        val bytes = ByteArrayOutputStream()
        JarOutputStream(bytes).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/services/apap.adapter.spi.ProviderAdapter"))
            jar.write(entryPoint.toByteArray())
            jar.closeEntry()
        }
        return PluginJar(bytes.toByteArray(), entryPoint)
    }

    @Test
    fun `scan loads a validly signed, SPI-compatible plugin from its real jar via an isolated classloader`(
        @TempDir tmp: Path,
    ) {
        writePlugin(tmp, "mock-provider", ">=2.0 <3.0", sign(adapterMockJarBytes))

        val registrations = manager().scan(tmp)

        assertEquals(1, registrations.size)
        assertEquals(PluginRegistrationStatus.LOADED, registrations.single().status)
        assertTrue(events.single() is PluginLoaded)
        assertEquals("mock-provider", (events.single() as PluginLoaded).pluginId)
    }

    @Test
    fun `the loaded adapter's class is loaded by an isolated classloader, not the test's own`(
        @TempDir tmp: Path,
    ) {
        writePlugin(tmp, "mock-provider", ">=2.0 <3.0", sign(adapterMockJarBytes))
        val mgr = manager()
        mgr.scan(tmp)

        val adapter = mgr.getAdapter("mock-provider")

        assertNotEquals(
            PluginManagerTest::class.java.classLoader,
            adapter::class.java.classLoader,
            "the plugin's ProviderAdapter must not share the test/core classloader (isolation boundary)",
        )
    }

    @Test
    fun `an invalid signature is quarantined and never reaches ServiceLoader`(
        @TempDir tmp: Path,
    ) {
        writePlugin(tmp, "bad-sig", ">=2.0 <3.0", signature = "not-a-real-signature")

        val registrations = manager().scan(tmp)

        assertEquals(PluginRegistrationStatus.QUARANTINED, registrations.single().status)
        assertFalse(registrations.single().signatureVerified)
        val quarantined = events.single() as PluginQuarantined
        assertEquals("bad-sig", quarantined.pluginId)
        assertTrue(quarantined.reason.contains("signature"))
        assertThrows(PluginNotFoundException::class.java) { manager().getAdapter("bad-sig") }
    }

    @Test
    fun `an incompatible spi_version range is quarantined even with a valid signature`(
        @TempDir tmp: Path,
    ) {
        writePlugin(tmp, "old-plugin", ">=99.0 <100.0", sign(adapterMockJarBytes))

        val registrations = manager(currentSpiVersion = SemVer(1, 0, 0)).scan(tmp)

        assertEquals(PluginRegistrationStatus.QUARANTINED, registrations.single().status)
        assertTrue((events.single() as PluginQuarantined).reason.contains("spi_version"))
    }

    /**
     * ADR-0043: `plugin.yaml`は人が書くメタデータ、`spiVersion()`はコード自身の申告であり、
     * 両者は独立に間違いうる。レンジしか見ていなかった頃は、古いSPIでビルドしたjarに
     * 新しいレンジを書いておくだけでロードされ、**実行時に`NoSuchMethodError`で落ちていた**。
     */
    @Test
    fun `a plugin whose manifest range disagrees with its code is quarantined`(
        @TempDir tmp: Path,
    ) {
        val jar = serviceOnlyJarFor(AdapterBuiltAgainstOlderSpi::class.java.name)
        // レンジはホスト（2.x）を含むので既存のマニフェスト検査は通る。落ちるのはコードの申告(1.1.0)。
        writePlugin(tmp, "lying-manifest", ">=2.0 <3.0", sign(jar.bytes), jar)

        val registrations = manager().scan(tmp)

        assertEquals(PluginRegistrationStatus.QUARANTINED, registrations.single().status)
        val reason = (events.single() as PluginQuarantined).reason
        assertTrue(reason.contains("manifest and code"), "何と何が食い違ったのかが分かりません: $reason")
        assertTrue(reason.contains("1.1.0"), "コードの申告値が示されていません: $reason")
        assertThrows(PluginNotFoundException::class.java) { manager().getAdapter("lying-manifest") }
    }

    /**
     * ホストと同じメジャーでも、**ホストより新しいマイナー**でビルドされたAdapterは、
     * ホストに存在しないSPIメンバを参照しうる。メジャー一致だけの判定では通ってしまう。
     */
    @Test
    fun `a plugin built against a newer SPI than the host is quarantined`(
        @TempDir tmp: Path,
    ) {
        val jar = serviceOnlyJarFor(AdapterBuiltAgainstNewerSpi::class.java.name)
        // マニフェストのレンジは広く、ホストもコードの申告も含む。落ちるのはホストとの突き合わせ。
        writePlugin(tmp, "from-the-future", ">=2.0 <4.0", sign(jar.bytes), jar)

        val registrations = manager().scan(tmp)

        assertEquals(PluginRegistrationStatus.QUARANTINED, registrations.single().status)
        val reason = (events.single() as PluginQuarantined).reason
        assertTrue(reason.contains("built against"), "ホストとの非互換であることが読めません: $reason")
        assertTrue(reason.contains(SpiSurface.version.toString()), "ホスト側の版が示されていません: $reason")
    }

    @Test
    fun `a plugin whose manifest, code and host agree is loaded`(
        @TempDir tmp: Path,
    ) {
        val jar = serviceOnlyJarFor(AdapterMatchingHostSpi::class.java.name)
        writePlugin(tmp, "consistent", ">=2.0 <3.0", sign(jar.bytes), jar)

        val registrations = manager().scan(tmp)

        assertEquals(PluginRegistrationStatus.LOADED, registrations.single().status)
        // 記録されるのはホストの版ではなく、検証済みのPlugin自身の申告値。
        assertEquals(SpiSurface.version, registrations.single().spiVersion)
        assertTrue(events.single() is PluginLoaded)
    }

    @Test
    fun `unload shuts down the adapter, publishes PluginUnloaded, and removes it from lookup`(
        @TempDir tmp: Path,
    ) {
        writePlugin(tmp, "mock-provider", ">=2.0 <3.0", sign(adapterMockJarBytes))
        val mgr = manager()
        mgr.scan(tmp)

        mgr.unload("mock-provider")

        assertTrue(events.last() is PluginUnloaded)
        assertThrows(PluginNotFoundException::class.java) { mgr.getAdapter("mock-provider") }
    }

    /**
     * `build.gradle.kts`が`:adapters:adapter-mock:jar`へ`dependsOn`するだけに留め（Task/Configuration
     * オブジェクトをビルドスクリプトのクロージャへ持ち込むとconfiguration cacheのシリアライズに
     * 失敗するため）、実際のjarパス探索はここで行う。Gradleの既定のTestタスク作業ディレクトリは
     * このモジュール自身のプロジェクトディレクトリ（`modules/apap-plugin`）のため、そこからの
     * 相対パスでadapter-mockの`build/libs`配下を規約的に探す。
     */
    private fun findAdapterMockJar(): Path {
        val librariesDir = Path.of("../../adapters/adapter-mock/build/libs").normalize()
        val jar =
            Files.list(librariesDir).use { stream ->
                stream.asSequence().firstOrNull { it.extension == "jar" }
            }
        return requireNotNull(jar) {
            "No adapter-mock jar found under $librariesDir " +
                "(run ':adapters:adapter-mock:jar' first, or run the whole suite via verify.sh)"
        }
    }
}
