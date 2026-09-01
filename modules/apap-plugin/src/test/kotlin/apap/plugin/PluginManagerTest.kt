package apap.plugin

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
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.time.Instant
import java.util.Base64
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

    private fun writePlugin(
        pluginsDir: Path,
        pluginId: String,
        spiVersionRange: String,
        signature: String,
        jarBytes: ByteArray = adapterMockJarBytes,
    ) {
        val dir = pluginsDir.resolve(pluginId).createDirectories()
        dir.resolve("plugin.yaml").writeText(
            """
            plugin_id: $pluginId
            version: 1.0.0
            spi_version: "$spiVersionRange"
            entry_point: apap.adapter.mock.MockProviderAdapter
            capabilities: [chat]
            auth_types: [api_key]
            signature: $signature
            """.trimIndent(),
        )
        dir.resolve("plugin.jar").writeBytes(jarBytes)
    }

    private fun manager(currentSpiVersion: SemVer = SemVer(1, 0, 0)) =
        PluginManager(eventPublisher, ids, clock, PluginSignatureVerifier(publicKey), currentSpiVersion)

    @Test
    fun `scan loads a validly signed, SPI-compatible plugin from its real jar via an isolated classloader`(
        @TempDir tmp: Path,
    ) {
        writePlugin(tmp, "mock-provider", ">=1.0 <2.0", sign(adapterMockJarBytes))

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
        writePlugin(tmp, "mock-provider", ">=1.0 <2.0", sign(adapterMockJarBytes))
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
        writePlugin(tmp, "bad-sig", ">=1.0 <2.0", signature = "not-a-real-signature")

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

    @Test
    fun `unload shuts down the adapter, publishes PluginUnloaded, and removes it from lookup`(
        @TempDir tmp: Path,
    ) {
        writePlugin(tmp, "mock-provider", ">=1.0 <2.0", sign(adapterMockJarBytes))
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
