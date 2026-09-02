package apap.hostcompat

import apap.runtime.ApapConfig
import apap.runtime.ApapEngine
import apap.runtime.ApapEngineBuilder
import java.nio.file.Path

/**
 * `docs/integration/prompt-engine.md` 2章のコード例の実体。
 *
 * `// docs:begin <id>` / `// docs:end <id>` で囲んだ範囲がドキュメントへ転記されており、
 * `DocumentedSnippetTest`が両者の一致を機械検証する（手作業の同期はしない）。
 *
 * Springの`@Configuration`/`@Bean`はホストのフレームワーク依存であり、このモジュールは
 * apap-runtime/apap-apiしか依存できない（ADR-0029）。そのため**フレームワーク非依存の
 * ファクトリ関数**として書き、Springで包む形はドキュメント側で説明のみ行う。
 * こうすることで「APAPをどう組み立てるか」の部分は必ずコンパイル検証される。
 */
object EngineBootstrap {
    // docs:begin build-engine

    /**
     * 依存ゼロ構成でも`build()`自体は成功する。ただしProvider未登録のため、そのままでは
     * どの`execute()`呼出も候補解決（FR-RTE-001）で失敗する。実運用では
     * `pluginDirectory(dir, trustedPublicKey)`か`adapterRegistry(...)`のいずれかを
     * 明示的に渡し、`ApapEngine.admin`経由でProvider/Modelを登録しておくこと。
     *
     * 戻り値の[ApapEngine]は[AutoCloseable]。`close()`がDRAINING→実行中完遂→Plugin unload
     * を行うので、ホストのシャットダウンフックへ必ず接続すること
     * （Springなら`@Bean(destroyMethod = "close")`）。
     */
    fun buildEngine(): ApapEngine = ApapEngineBuilder().build()
    // docs:end build-engine

    // docs:begin build-engine-from-config

    /**
     * 03_基本設計.md 3.15の`application.yaml`形式でSPIを宣言的に選ぶ場合。
     * [ApapConfig]はファイル/Map/プログラマティックの3経路で構築できる。
     */
    fun buildEngineFromConfigFile(configFile: Path): ApapEngine =
        ApapEngineBuilder()
            .applyConfig(ApapConfig.fromYamlFile(configFile))
            .build()

    /** ホストの設定機構（Springの`Environment`等）から組み立てる場合はこちら。 */
    fun buildEngineFromConfigMap(settings: Map<String, String>): ApapEngine =
        ApapEngineBuilder()
            .applyConfig(ApapConfig.fromMap(settings))
            .build()
    // docs:end build-engine-from-config
}
