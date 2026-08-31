package apap.plugin

import apap.adapter.spi.ProviderAdapter
import apap.adapter.spi.plugin.PluginManifest
import apap.adapter.spi.plugin.PluginManifestParser
import apap.domain.event.EventMetadata
import apap.domain.event.PluginLoaded
import apap.domain.event.PluginQuarantined
import apap.domain.event.PluginUnloaded
import apap.domain.model.plugin.PluginRegistration
import apap.domain.model.plugin.PluginRegistrationStatus
import apap.domain.model.vo.SemVer
import apap.domain.port.Clock
import apap.domain.port.DomainEventPublisher
import apap.domain.port.IdGenerator
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.name

class PluginNotFoundException(
    pluginId: String,
) : NoSuchElementException("No loaded plugin for id: $pluginId")

class PluginEntryPointNotFoundException(
    pluginId: String,
) : IllegalStateException(
        "Plugin $pluginId's jar declares no ServiceLoader-discoverable ${ProviderAdapter::class.simpleName} " +
            "(missing META-INF/services/${ProviderAdapter::class.qualifiedName} entry)",
    )

/**
 * 16_拡張ポイント.md 16.1 / 15_Provider追加手順.md 15.1 Step4 / 10_アクティビティ図.md
 * （Plugin Manager: scan→署名検証→SPI適合確認→分岐）。
 *
 * ライフサイクル: scan → verify → LOADED → initialize(稼働) → drain → shutdown → UNLOADED。
 * 不適合はQUARANTINED（[16_拡張ポイント.md] 15行目）。この状態名・用語は設計書のまま使う
 * （CLAUDE.md実装規約「イベントは14章の名前と完全一致」と同じ精神を状態名にも適用）。
 *
 * scan/verify/initialize/drain/shutdownは[PluginRegistration]（LOADED/UNLOADED/QUARANTINEDの
 * 3状態のみを持つ永続化対象Aggregate）そのものの状態ではなく、本クラスが実行する**手順**である。
 * 「稼働」中のAdapterインスタンス生成（ServiceLoader経由）までがこのクラスの責務であり、
 * Provider固有設定を伴う`ProviderAdapter.initialize(config, secrets)`の実際の呼出は、
 * Provider登録フロー（15.1 Step4以降、本タスクの範囲外）が別途行う——1個のPluginは複数の
 * Provider登録から共有され得るため、Plugin単位のロード時点ではProvider固有設定を持てない。
 *
 * 分離: `apap-plugin`は`apap-domain`+`apap-adapter-spi`のみに依存し、コアのDIには参加しない
 * （CLAUDE.md不変条件6と同じ精神）。ロードしたPluginは分離`URLClassLoader`
 * （親を`ClassLoader.getPlatformClassLoader()`とし、コア実装クラス（apap-domain等）を
 * 見せない）でロードし、コア側へAdapterの実装クラスを漏らさない。
 */
class PluginManager(
    private val eventPublisher: DomainEventPublisher,
    private val idGenerator: IdGenerator,
    private val clock: Clock,
    private val signatureVerifier: PluginSignatureVerifier,
    private val currentSpiVersion: SemVer,
) {
    private data class LoadedPlugin(
        val registration: PluginRegistration,
        val manifest: PluginManifest,
        val classLoader: URLClassLoader,
        val adapter: ProviderAdapter,
    )

    private val loaded = ConcurrentHashMap<String, LoadedPlugin>()
    private val quarantined = ConcurrentHashMap<String, PluginRegistration>()

    /**
     * [pluginsDir]直下の各サブディレクトリを1 Pluginとしてscanする（規約:
     * `<pluginsDir>/<任意のディレクトリ名>/plugin.yaml` + `.../plugin.jar`）。
     * 15.1 Step4の表の#1〜#2（配置→検証→LOADED/QUARANTINED）に相当する。
     */
    fun scan(pluginsDir: Path): List<PluginRegistration> {
        if (!Files.isDirectory(pluginsDir)) return emptyList()
        return Files
            .list(pluginsDir)
            .use { stream ->
                stream.filter { it.isDirectory() }.sorted().toList()
            }.map(::loadOne)
    }

    fun getAdapter(pluginId: String): ProviderAdapter {
        val plugin = loaded[pluginId] ?: throw PluginNotFoundException(pluginId)
        return plugin.adapter
    }

    fun registration(pluginId: String): PluginRegistration? = loaded[pluginId]?.registration ?: quarantined[pluginId]

    /** LOADEDなPluginのマニフェスト（[apap.provider.AdapterRegistry]のような橋渡し実装が使う）。 */
    fun manifest(pluginId: String): PluginManifest? = loaded[pluginId]?.manifest

    /** 現在LOADED状態のPlugin IDの一覧（`close()`時の一括unload等、host側の走査用）。 */
    fun loadedPluginIds(): Set<String> = loaded.keys.toSet()

    /**
     * drain（新規解決の停止）→`adapter.shutdown()`→UNLOADED→[PluginUnloaded]発火→
     * 分離ClassLoaderの破棄。「新規リクエストの受付停止」自体は本クラスが解決経路
     * （`apap-provider`の`AdapterRegistry`）を持たないため関知できない: 呼び出し側が
     * [getAdapter]経由の新規解決を止めてから本メソッドを呼ぶ運用を前提とする
     * （要件充足に影響しない実装判断のためADR化せずここに根拠を記す）。
     */
    fun unload(pluginId: String) {
        val plugin = loaded.remove(pluginId) ?: throw PluginNotFoundException(pluginId)
        runCatching { plugin.adapter.shutdown() }
            .onFailure { e -> logger.warn("adapter.shutdown() failed for plugin={}: {}", pluginId, e.message, e) }
        plugin.classLoader.close()
        eventPublisher.publish(
            PluginUnloaded(eventMetadata(pluginId), pluginId),
        )
    }

    @Suppress("ReturnCount") // guard-clause style (scan/verify/quarantine chain from 10_アクティビティ図.md).
    private fun loadOne(pluginDir: Path): PluginRegistration {
        val pluginLabel = pluginDir.name
        val manifest =
            runCatching { PluginManifestParser.parse(Files.readString(pluginDir.resolve(MANIFEST_FILE_NAME))) }
                .getOrElse { e ->
                    return quarantine(pluginLabel, manifest = null, reason = "plugin.yaml parse error: ${e.message}")
                }
        val jarBytes =
            runCatching { Files.readAllBytes(pluginDir.resolve(JAR_FILE_NAME)) }
                .getOrElse { e ->
                    return quarantine(manifest.pluginId, manifest, "plugin.jar unreadable: ${e.message}")
                }

        if (!manifest.spiVersionRange.contains(currentSpiVersion)) {
            return quarantine(
                manifest.pluginId,
                manifest,
                "incompatible spi_version: plugin requires ${manifest.spiVersionRange}, host is $currentSpiVersion",
            )
        }
        if (!signatureVerifier.verify(jarBytes, manifest.signature)) {
            return quarantine(manifest.pluginId, manifest, "signature verification failed")
        }

        return load(manifest, pluginDir.resolve(JAR_FILE_NAME))
    }

    private fun load(
        manifest: PluginManifest,
        jarPath: Path,
    ): PluginRegistration {
        // 分離境界: 親を`ProviderAdapter`自身のクラスローダ（=SPI契約が乗っているクラスローダ）に
        // する。`ServiceLoader.load(ProviderAdapter::class.java, classLoader)`が返すインスタンスを
        // コア側の`ProviderAdapter`型と同一視できるためにはSPI型そのものは共有する必要がある
        // （そうしないとPlugin側とコア側で「別の」ProviderAdapterクラスとして扱われ、
        // ServiceLoaderが何も見つけられない）。一方、Plugin自身の実装クラス・Plugin固有の依存は
        // 親クラスローダに存在しない限りこの子ClassLoaderからのみ解決され、コアの他クラス
        // （apap-execution/apap-runtime等）へは波及しない——プラットフォームクラスローダを親にする
        // より緩いが実用的な分離境界（要件充足に影響しない実装判断のためADR化せずここに根拠を記す）。
        val classLoader = URLClassLoader(arrayOf(jarPath.toUri().toURL()), ProviderAdapter::class.java.classLoader)
        val adapter =
            ServiceLoader.load(ProviderAdapter::class.java, classLoader).firstOrNull()
                ?: run {
                    classLoader.close()
                    return quarantine(manifest.pluginId, manifest, "entry point not found: ${manifest.entryPoint}")
                }

        val registration =
            PluginRegistration(
                pluginId = manifest.pluginId,
                version = manifest.version,
                spiVersion = currentSpiVersion,
                signature = manifest.signature,
                signatureVerified = true,
            ).load()
        loaded[manifest.pluginId] = LoadedPlugin(registration, manifest, classLoader, adapter)
        quarantined.remove(manifest.pluginId)
        eventPublisher.publish(
            PluginLoaded(eventMetadata(manifest.pluginId), manifest.pluginId, manifest.version.toString()),
        )
        return registration
    }

    private fun quarantine(
        pluginId: String,
        manifest: PluginManifest?,
        reason: String,
    ): PluginRegistration {
        logger.warn("plugin quarantined id={}: {}", pluginId, reason)
        val registration =
            PluginRegistration(
                pluginId = pluginId,
                version = manifest?.version ?: SemVer(0, 0, 0),
                spiVersion = currentSpiVersion,
                signature = manifest?.signature ?: UNKNOWN_SIGNATURE_PLACEHOLDER,
                signatureVerified = false,
                status = PluginRegistrationStatus.QUARANTINED,
            )
        quarantined[pluginId] = registration
        eventPublisher.publish(PluginQuarantined(eventMetadata(pluginId), pluginId, reason))
        return registration
    }

    private fun eventMetadata(pluginId: String): EventMetadata =
        EventMetadata(
            eventId = idGenerator.newId(),
            occurredAt = clock.now(),
            traceId = idGenerator.newId(),
            tenantId = null,
            aggregateId = pluginId,
            version = 0,
        )

    private companion object {
        val logger = LoggerFactory.getLogger(PluginManager::class.java)
        const val MANIFEST_FILE_NAME = "plugin.yaml"
        const val JAR_FILE_NAME = "plugin.jar"

        // PluginRegistration.signatureはblank禁止のため、manifest解析自体に失敗しsignatureが
        // 不明な場合のプレースホルダ。実際の空文字とは区別できるようにする。
        const val UNKNOWN_SIGNATURE_PLACEHOLDER = "<unknown>"
    }
}
