# prompt-engine連携ガイド（P9）

> このドキュメントは `apap-engine`（本リポジトリ）が提供する埋込用ライブラリ `modules/apap-runtime`
> （成果物 `apap-runtime`、型 `ApapEngine`/`ApapEngineBuilder`）を、別プロジェクト
> `prompt-engine`（`/Users/io/projects/GitHub/engine/prompt-engine`、Spring Boot）へ埋め込むための
> 実務手順を記す。`docs/design/*.md` は編集しない方針のため、設計書に書かれていない実装判断・
> 現物確認済みの事実はすべてここに書く。

## 0. 前提: prompt-engine側の現状（2026-08-31時点で現物確認済み）

- `promptengine.domain.execution.ExecutionAdapter`（`prompt-engine-domain`）が委譲の唯一の入口。
  `fun execute(prompt: RenderedPrompt, policy: ExecutionPolicy): RawResponse`
  （**非suspend**。prompt-engineはまだcoroutinesを一切使っていない——後述4章参照）。
- 配線点は `promptengine.bootstrap.config.ExecutionConfig.executionAdapter`
  （`prompt-engine-bootstrap`）の`when (providerProperties.provider)`分岐:
  `"apap" -> error("... not yet implemented; tracked in Issue #31 and ADR-0031")`。
  ここへ実アダプタを実装して差し込むのがゴール。
- ADR-0031（prompt-engine側）で「APAPは独立基盤として別途構築する」方針は既に確定済み。
  本ドキュメントはその実装編にあたる。
- Issue #31「APAP統合時にリトライ責務の重複を解消する」が既に追跡中（3章参照）。

## 1. 追加する依存

`prompt-engine`のGradleモジュール構成は「具象クラスのDI結線は`prompt-engine-bootstrap`
（Composition Root）のみで行う」という規約を既に持つ（同モジュール`build.gradle.kts`の
コメント）。これに従い、依存は2箇所・2種類のみ追加する（新規外部依存座標としては
`apap-runtime`と`apap-api`の2つのみ）:

| モジュール | 追加する依存 | 理由 |
|---|---|---|
| `prompt-engine-infrastructure` | `implementation(apap-runtime)`, `implementation(apap-api)` | `ApapExecutionAdapter`（`ExecutionAdapter`実装）本体をここに置く（`ExecutionAdapter`のKDoc「実APAP接続は`prompt-engine-infrastructure`」と一致）。`ApapEngine`型（apap-runtime）と`ApapRequest`/`ApapResponse`（apap-api）の両方を参照するため両方必要 |
| `prompt-engine-bootstrap` | `implementation(apap-runtime)` | `ApapEngineBuilder`で`ApapEngine`シングルトンを構築し`@Bean(destroyMethod = "close")`で公開するのはComposition Rootの責務。`apap-api`型は直接参照しないため不要 |

`apap-runtime`は`api(project(":modules:apap-domain"))`/`api(project(":modules:apap-provider"))`
としているため（`ApapAdmin`のシグネチャがこれらの型を公開するため）、`apap-domain`/
`apap-provider`はGradleの推移的依存として自動的に見える。**これらをprompt-engine側の
`build.gradle.kts`へ明示的に追加してはならない**（依存宣言を「apap-runtime, apap-apiの2つのみ」
に保つ意図が壊れる）。

## 2. 最小の初期化コード

### 2-a. `prompt-engine-bootstrap`: `ApapEngine`を構築する

まず**フレームワーク非依存**の組み立て。この部分は`integration/host-compat`で実際に
コンパイルされており（ホストと同じ依存だけで）、ドキュメントとコードの一致は
`DocumentedSnippetTest`が機械検証している（ADR-0029）。


<!-- docs:build-engine src=integration/host-compat/src/main/kotlin/apap/hostcompat/EngineBootstrap.kt -->
```kotlin
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
```

これをSpringのBeanとして公開する場合は次のように包む。**`destroyMethod = "close"`を必ず付ける**
（付けないとシャットダウン時にDRAINING→実行中完遂→Plugin unloadが走らない）。

<!-- docs:illustrative reason=Springはhost-compatの依存に無いため（apap-runtime/apap-apiのみ）コンパイル検証の対象外 -->
```kotlin
@Configuration
class ApapEngineConfig {
    @Bean(destroyMethod = "close")
    fun apapEngine(): ApapEngine = EngineBootstrap.buildEngine()
}
```

依存ゼロ構成（`ApapEngineBuilder()`に何も渡さない）でも`build()`自体は成功する
（`ApapEngineBuilderTest`の`zero-dependency build ...`系テスト参照）。ただしその場合
Provider未登録のためどの`execute()`呼出も`FR-RTE-001`の候補解決で失敗する。
本番投入前に`ApapEngine.admin`経由でProvider/Model登録を行う運用手順（Admin API相当）を
別途prompt-engine側で用意すること（本ガイドの範囲外）。

### 2-a-2. 設定ファイルでSPIを選ぶ（`ApapConfig`、設計書3.15）

コードで直接インスタンスを渡す代わりに、03_基本設計.md 3.15の`application.yaml`形式で
宣言的にSPIを選ぶこともできる。`ApapConfig`はファイル/Map/プログラマティックの3経路で
構築でき、`ApapEngineBuilder.applyConfig(config)`で束縛する。

```yaml
# apap.yaml（3.15の形式。apap:直下にドット区切りの平坦なキーを並べる）
apap:
  routing.strategy: weighted-score
  retry.strategy: exp-backoff-jitter
  cache.store: in-memory
  secret.store: env-var
  compaction.strategy: truncate-oldest
  plugin.signature.required: true
```

<!-- docs:build-engine-from-config src=integration/host-compat/src/main/kotlin/apap/hostcompat/EngineBootstrap.kt -->
```kotlin
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
```

**名前で選べるのは引数無しで構築できる組込み実装だけ**である。3.15の例示値のうち
`cache.store: distributed-kvs`と`secret.store: vault-compatible`は接続情報（ホスト・認証情報）を
名前だけでは決められないため名前解決の対象外で、`applyConfig`は例外を投げる
（既定値へ黙ってfall backしない）。これらを使う場合は実装インスタンスを
`cacheStore(...)`/`secretStore(...)`へ直接渡すこと。特に`distributed-kvs`
（`RedisCacheStore`）は`apap-infrastructure-distributed`の実装であり、名前解決可能にすると
`apap-runtime`が同モジュールへ依存してしまう（7章の「既定構成の依存グラフ」制約に反する）。

`plugin.dir`を設定ファイルで指定した場合、署名検証の信頼鍵は設定ファイルに書けないため
`pluginTrustedPublicKey(key)`で別途渡す必要がある。鍵を渡さずに`build()`すると例外になる
（署名検証なしのPluginロードへ黙って縮退させない）。`plugin.signature.required: false`も
同様に受け付けず例外とする（署名検証は無効化できない）。

### 2-b. `AiExecutionPort`パターン: prompt-engine側にPortを立て、APAP実装を注入する

prompt-engineは既に`ExecutionAdapter`という名のPortを持っているため、**新たに
`AiExecutionPort`を追加する必要はない**。`ExecutionAdapter`自身がそのPortである。
推奨パターンは「`ExecutionAdapter`の実装として`ApapExecutionAdapter`を書き、
`ApapEngine`をコンストラクタ注入する」——Ports & Adaptersとして既に正しい形。

<!-- docs:execution-adapter src=integration/host-compat/src/main/kotlin/apap/hostcompat/ApapExecutionAdapter.kt -->
```kotlin
class ApapExecutionAdapter(
    private val apapEngine: ApapEngine,
    private val tenantId: TenantId,
    private val capabilityId: CapabilityId = CapabilityId("chat"),
) : ExecutionAdapter {
    /**
     * ホスト側の`execute`は非suspend、`ApapEngine.execute`はsuspend。
     * `runBlocking`でのブリッジをこの境界1箇所に閉じ込める。
     */
    override fun execute(
        prompt: RenderedPrompt,
        policy: ExecutionPolicy,
    ): RawResponse {
        val startNanos = System.nanoTime()
        return try {
            val response =
                runBlocking {
                    apapEngine.execute(
                        ApapRequest(
                            tenantId = tenantId,
                            principal = "prompt-engine",
                            capabilityId = capabilityId,
                            input = prompt.messages.map { ContentPart.Text(it.content) },
                            timeoutBudget = Duration.ofMillis(policy.timeoutMs),
                        ),
                    )
                }
            RawResponse(
                content =
                    SensitiveValue.of(
                        response.output.filterIsInstance<ContentPart.Text>().joinToString("") { it.text },
                    ),
                usage =
                    HostUsage(
                        HostTokenCount(response.usage.inputTokens.value),
                        HostTokenCount(response.usage.outputTokens.value),
                    ),
                latency = LatencyMs((System.nanoTime() - startNanos) / NANOS_PER_MILLI),
            )
        } catch (e: ApapException) {
            // 実行系の失敗はすべて apap.api.ApapException へ正規化されている。
            // 内部例外（apap.execution.* 等）はホストから見えないのでcatchしてはならない。
            throw ExecutionFailedException(e.error.toExecutionErrorType(), retryCount = 0, cause = e)
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
```

`ApapRequest.principal`に固定文字列`"prompt-engine"`を置いているのは仮実装であり、
実際には呼出元ユーザー/セッション識別子を`RenderedPrompt`より上流（`ExecutionPolicy`拡張か、
`ExecutionAdapter.execute`のシグネチャ変更）から渡す設計が必要になる
（本ガイドの範囲外、prompt-engine側でVOを増やす判断が要る）。同様に`tenantId`も
コンストラクタ固定ではなくリクエスト単位で決まるのが自然だが、現在の
`ExecutionAdapter.execute(prompt, policy)`シグネチャにはテナント識別子を運ぶ場所がない
——prompt-engine側の設計判断が必要な箇所として明記する。

### 2-b-2. 実行系の失敗は `apap.api.ApapException` で受ける

`ApapEngine.execute`/`executeStream`が投げる実行系の失敗は、すべて`apap.api.ApapException`へ
正規化されている。**内部例外（`apap.execution.ExecutionFailedException`等）を直接catchしては
ならない**——`apap-runtime`は`apap-execution`/`apap-routing`/`apap-context`を`implementation`
スコープで依存しているため、それらの型は埋込ホストからそもそもコンパイル時に見えない
（P10着手時にGatewayを実装して判明した。本ガイドの初版はこの点を誤っており、
提示していたcatch節はホスト側でコンパイルできなかった）。

`ApapException.error`は`NormalizedError`（`apap-domain`、`apap-runtime`が`api`スコープで公開）で、
13.4のコード・`retryable`・`retry_after_ms`を保持する。**エラー分類はエンジン側で確定済み**
なので、ホストはこれを読むだけでよく、例外型を見て分類をやり直さないこと。

### 2-c. `ExecutionErrorType`マッピング

APAPの`NormalizedError.category`（`AdapterErrorCategory`）→prompt-engineの
`ExecutionErrorType`の対応表（本ガイドの推奨、Issue #31の解消判断はprompt-engine側に委ねる）:

| APAP `AdapterErrorCategory` | `retryable` | → prompt-engine `ExecutionErrorType` |
|---|---|---|
| `RATE_LIMITED` | true | `RATE_LIMITED` |
| `TRANSIENT` | true | `SERVER_ERROR` |
| `PROVIDER_UNAVAILABLE` | true | `SERVER_ERROR` |
| `MODEL_ERROR` | 通常false | `CLIENT_ERROR` |
| `INVALID_REQUEST` | false | `CLIENT_ERROR` |
| `AUTH_ERROR` | false | `CLIENT_ERROR` |
| `CONTENT_FILTERED` | false | `CLIENT_ERROR` |
| `UNSUPPORTED_CAPABILITY` | false | `CLIENT_ERROR` |
| 上記以外・分類不能 | - | `UNKNOWN` |

<!-- docs:error-mapping src=integration/host-compat/src/main/kotlin/apap/hostcompat/ApapExecutionAdapter.kt -->
```kotlin
/**
 * 13.4のコード体系（`NormalizedError`が保持）からホスト側の分類へ写す。
 *
 * **エラー分類をやり直さない**のが要点。retryableかどうかはAPAP側（2.11の表）で確定済みで、
 * `CONNECT_TIMEOUT`/`READ_TIMEOUT`のような「未送信と言い切れるか」の判定も
 * Provider⇔APAP間の境界でAPAPが済ませている。ホストは`retryable`を読むだけでよい。
 */
fun NormalizedError.toExecutionErrorType(): ExecutionErrorType =
    when {
        category == AdapterErrorCategory.RATE_LIMITED -> ExecutionErrorType.RATE_LIMITED
        retryable -> ExecutionErrorType.SERVER_ERROR
        else -> ExecutionErrorType.CLIENT_ERROR
    }
```

**`CONNECT_TIMEOUT`/`READ_TIMEOUT`の区別が消える理由**: prompt-engineがこの2値を分けるのは
「未送信と確実に言えるか（二重課金防止）」を、直接HTTP接続するアダプタが自前で判定する
必要があったため（ADR-0014決定7、ADR-0029決定3・4）。APAPを埋込ライブラリとして呼ぶ構成では
prompt-engine⇔APAP間はプロセス内関数呼出であり、Provider⇔APAP間のネットワーク境界の
安全性判定は**APAP自身の`NormalizedError.retryable`が既に行っている**
（02_システム仕様.md 2.11の表）。そのためこの2値の区別を呼出側で再現する必要はない
——`retryable`のみを見れば足りる。

**Retry責務の重複について（Issue #31宛のメモ）**: APAPの`execute()`が例外を投げて返ってくる
時点で、APAP内部のRetry（既定最大3回）とFallback（既定3段）は**既に使い切られている**。
`RetryingExecutionAdapter`によるprompt-engine側の追加リトライは、単純な「同じ失敗の繰り返し」
ではなく「APAPへの新規`execute()`呼出＝新しいRouting決定（別Provider/Modelの再選定）」を
意味するため、二重リトライというより多段防御に近い。ただし`policy.maxRetries`と
APAP内部の既定値の掛け算で最大試行回数が意図せず膨れる点（例: PE側2回×APAP内部3回＝
最悪6回のProvider呼出）は実際の重複であり、Issue #31で判断すべき論点として引き継ぐ。
`retryAfterMs`（`NormalizedError`が保持、レート制限時のProvider指定待機時間）は現在の
`ExecutionFailedException`に対応するフィールドがなく、素通しできない
——値を活用したい場合は`ExecutionFailedException`へのフィールド追加がprompt-engine側で必要。

## 3. ストリーミングのブリッジ（新規未踏領域であることの明記）

prompt-engineは現時点で**coroutines/`Flow`を一切使っていない**
（`ExecutionAdapter.execute`が非suspendである通り）。`ApapEngine.executeStream(request): Flow<ApapStreamChunk>`
をprompt-engine側へブリッジするには、少なくとも次のいずれかの設計判断がprompt-engine側で
必要になる（本ガイドはどちらか一方を推奨しない。既存のPE設計思想=非同期皆無、との整合を
考慮した意思決定が要る）:

1. `ExecutionAdapter`に`executeStream`相当のメソッドを追加し、`Flow<T>`または
   `kotlin.sequences.Sequence<T>`／コールバック型（`(T) -> Unit`）のいずれかで返す新規Port
   シグネチャを設計する（既存の`execute`は変えない、追加のみ）。
2. Streamingを当面サポート対象から外し、`ApapEngine.execute`（非Streaming）のみを繋ぐ
   （FR-CAP-004はAPAP側では実装済みだが、PE側での消費経路が無い状態を許容する）。

**このドキュメントではどちらか一方を選定しない**（prompt-engine側のアーキテクチャ判断であり、
APAP側から強制すべきでないため）。ただし**どちらもAPAP側から見て型として成立すること**は
確認済みで、次の2つは`integration/host-compat`で実際にコンパイルされている。

方式1: `Flow`のまま渡す。

<!-- docs:streaming-flow src=integration/host-compat/src/main/kotlin/apap/hostcompat/StreamingBridge.kt -->
```kotlin
/**
 * 方式1: `Flow`のまま渡す。ホストがcoroutinesを受け入れられるなら最も素直で、
 * バックプレッシャ（2.10のpull型）もそのまま活きる。
 * テキストデルタだけを取り出す例。
 */
fun textDeltas(
    engine: ApapEngine,
    request: ApapRequest,
): Flow<String> =
    engine
        .executeStream(request)
        .mapNotNull { chunk ->
            if (chunk.type == ApapStreamChunkType.CONTENT_DELTA) {
                (chunk.delta as? ContentPart.Text)?.text
            } else {
                null
            }
        }
```

方式2: コールバックで押し出す（ホストへcoroutinesを持ち込みたくない場合）。

<!-- docs:streaming-callback src=integration/host-compat/src/main/kotlin/apap/hostcompat/StreamingBridge.kt -->
```kotlin
/**
 * 方式2: コールバックで押し出す。非同期を持ち込みたくないホスト向け。
 *
 * **注意**: `runBlocking`はストリーム全体を1スレッドで待つ。SSE等へ逐次書き出すなら
 * 呼び出し側が専用スレッド/ディスパッチャで実行すること（そうしないと
 * 「逐次」の意味が失われ、全チャンク受信後にまとめて処理される形になる）。
 */
fun forEachChunk(
    engine: ApapEngine,
    request: ApapRequest,
    onChunk: (ApapStreamChunk) -> Unit,
) {
    runBlocking {
        engine.executeStream(request).collect { chunk -> onChunk(chunk) }
    }
}
```

参考として、`Flow<ApapStreamChunk>`を同期的な
`Iterator<ApapStreamChunk>`へ変換するだけなら`kotlinx.coroutines.flow.Flow.asIterable()`
（`runBlocking`のスコープ内で使う）が最小の橋渡しになる——ただしこれは「非同期性を
捨てて同期的に全チャンクを待つ」ものではなく、`Iterator.next()`呼出のたびに1チャンク分だけ
`runBlocking`する形になるため、SSE等への逐次書き出しとは相性が悪い。実際の設計は
prompt-engine側のPresentation層（Controller/SSE実装）の要求に応じて別途検討すること。

## 4. adapter-mockでの差替方法

`ApapEngineBuilder.adapterRegistry(registry: AdapterRegistry)`で任意の`AdapterRegistry`を
注入できる。テストでは`adapters:adapter-mock`（`apap.adapter.mock.MockProviderAdapter`/
`MockAdapterConfig`）を使い、実Provider Plugin配置なしに`ApapEngine`をE2Eで動かせる
（`modules/apap-runtime/src/test/kotlin/apap/runtime/ApapEngineBuilderTest.kt`が実例）。

<!-- docs:adapter-mock-substitution src=integration/host-compat/src/test/kotlin/apap/hostcompat/AdapterMockSubstitutionTest.kt -->
```kotlin
/**
 * `ApapEngineBuilder.adapterRegistry(...)`へ任意の[AdapterRegistry]を渡すと、
 * 実Provider Pluginを配置せずに`ApapEngine`を動かせる。
 *
 * 注意: `Provider.beginValidation`→`completeValidation`はAdapterの
 * `validateCredential`/`healthCheck`/`supportedCapabilities`を実際に呼ぶため、
 * Adapterは`initialize`済みである必要がある。
 */
fun mockAdapterRegistry(capabilityId: CapabilityId): AdapterRegistry {
    val region = Region.of("jp-east", RegionCodeTable(setOf("jp-east")))
    val adapter = MockProviderAdapter(MockAdapterConfig(supportedCapabilities = setOf(capabilityId)))
    adapter.initialize(
        AdapterConfig(
            ProviderId("01ARZ3NDEKTSV4RRFFQ69G5FD1"),
            listOf(Endpoint("ep1", region, "https://example.internal", 100)),
            RateLimits(600, 100_000, 10),
            setOf(region),
        ),
        object : SecretAccessor {
            override fun resolve(ref: CredentialRef): SecretValue = SecretValue("secret".toCharArray())
        },
    )
    val manifest =
        PluginManifest(
            pluginId = "plugin-a",
            version = SemVer(1, 0, 0),
            spiVersionRange = SemVerRange.parse(">=1.0"),
            entryPoint = "test.Entry",
            capabilities = setOf(capabilityId),
            authTypes = setOf("api_key"),
            signature = "sig",
        )
    return object : AdapterRegistry {
        override fun resolve(pluginId: String): ResolvedPlugin {
            if (pluginId != "plugin-a") throw PluginNotFoundException(pluginId)
            return ResolvedPlugin(adapter, manifest)
        }
    }
}
```

**命名規約の衝突に注意**: apap-engine自身のテスト用Provider Adapterは`MockProviderAdapter`
（`Mock*`接頭辞）。一方prompt-engine側のテストダブル命名規約は`Fake*`
（`Stub*`/`Mock*`/`InMemory*`は使わない、例: `FakeExecutionAdapter`）。prompt-engine側で
`ApapEngine`自体をテストダブル化したい場合（`ApapExecutionAdapter`の単体テスト等）は、
prompt-engine自身の規約に従い`FakeApapEngine`のように命名すること
（`apap.adapter.mock.MockProviderAdapter`をそのままprompt-engine側のテストへ持ち込むのは、
別の依存境界の型を混入させるだけでなく命名規約上も一貫しない）。

## 5. バージョニング方針

- **`apap-runtime`（成果物）**: semver。CLAUDE.md不変条件・ADR-0016の枠組みに従い、
  `apap-runtime`自体の公開API（`ApapEngine`/`ApapEngineBuilder`/`ApapAdmin`/`ApapConfig`/
  `ApapHealth`とその公開DTO）の破壊的変更はメジャーバージョンでのみ行う。
- **`apap-api`（公開DTOモジュール）**: `apap-domain`のVOを直接再利用している
  （`ApapRequest`/`ApapResponse`のKDoc参照）。ADR-0016のような明示的なSPI境界
  （`SpiSurface`一覧＋`SpiSurfaceTest`によるKonsist機械検証）は`apap-api`にはまだ無い
  ——`apap-domain`のVOに破壊的変更が入ると、現状`apap-api`の破壊的変更としても波及しうる
  制約として残っている（`apap.api.ApapRequest`のKDoc「要件充足に影響しない実装判断」節参照）。
  厳密なSPI面管理が必要になった場合は、ADR-0016と同型の`SpiSurface`相当の仕組みを
  `apap-api`にも導入するADRを別途起票すること。
- **SPI公開面全般（`apap-adapter-spi`のtypealias境界）**: ADR-0016参照。typealiasは
  ソースレベル分離のみを提供し、バイナリ／依存関係レベルの分離は提供しない
  （実体は常に`apap.domain.*`の型）。

## 6. Jacksonバージョン整合（ADR-0017の要約、詳細は同ADR参照）

- prompt-engine（宿主）は`gradle/libs.versions.toml`で`jackson`を独自宣言しており、
  Spring Boot BOM（`spring-boot-dependencies`、`implementation(platform(...))`）の管理下で
  実際に使われるJacksonバージョンは、**このカタログ値ではなくSpring Boot BOMが決める**
  （2026-08-31確認: prompt-engine側カタログの`jackson`エントリは`2.22.2`だが、これは
  実際の依存解決に強制されない参考値）。`prompt-engine-bootstrap`の
  `implementation(libs.jackson.module.kotlin)`にバージョン指定がないのはこのため。
- apap-engine側は`gradle/libs.versions.toml`で`jackson = "2.22.1"`を明示宣言し
  （ADR-0017決定0-a）、`apap-provider`の`json-schema-validator`が推移的に要求する
  `2.18.3`を上書きしている。**この`2.22.1`はADR-0017策定時点（2026-08-21）の
  prompt-engine側宣言値のスナップショットであり、上記の通りprompt-engine側カタログは
  既に`2.22.2`へ進んでいる（2026-08-31確認、1パッチ差でドリフト済み）。** 実結線時には
  必ず両リポジトリの`gradle/libs.versions.toml`の`jackson`エントリを直接diffし、
  apap-engine側を追従させること（ADR-0017は「自動追従の仕組みはない、レビュー時の目視のみ」
  と明記しており、本ガイドの日付以降さらにドリフトしている可能性がある）。
- JSONスタックはプロジェクト全体でJacksonに一本化する方針（ADR-0017決定0-b）。
  `apap-domain`/`apap-adapter-spi`/`apap-provider`/`apap-runtime`（およびこれらが依存する
  他モジュール）へ`kotlinx-serialization`/Gson/`org.json`/Moshi等を追加しないこと。

### 6-a. 宿主が`enforcedPlatform`を使う場合の注意

apap-engineが要求するJacksonは**下限**要求である（`apap-provider`の
`json-schema-validator:1.5.9`が2.x系を必要とし、apap-engineはこれを`libs.versions.toml`の
`jackson`エントリで引き上げている）。上限は設けていないため、宿主がより新しい2.x系へ
引き上げる分には問題ない。

prompt-engineは現在`implementation(platform(...))`（Gradleネイティブの`platform`）でのみ
Spring Boot BOMを適用しており、Gradleの既定解決戦略（同一classpath上の要求のうち最高
バージョンを採用）に従うため、apap-engine側の要求と自動的に収束する（ADR-0017が
「ハード強制していない」と確認した状態）。**この前提が崩れるのは宿主が
`enforcedPlatform`（または`resolutionStrategy.force` / `strictly()`）へ切り替えた場合**で、
このときBOMの指定値が上限としても強制され、Gradleは高い方へ収束しなくなる:

- Spring Boot BOMのJacksonがapap-engineの要求を**下回る**場合、
  ビルドは通るが実行時に`NoSuchMethodError`/`NoClassDefFoundError`として現れる
  （コンパイル時には検出されない。ADR-0017が「最頻出の実行時障害」として挙げている形）。
- したがって`enforcedPlatform`へ切り替える場合は、切替時に
  `./gradlew :modules:prompt-engine-bootstrap:dependencies --configuration runtimeClasspath`
  で`jackson-databind`の解決結果を確認し、apap-engine側の`libs.versions.toml`の
  `jackson`値以上であることを確かめること。下回る場合は、宿主側で
  `jackson-databind`への明示的な`constraints`を追加して引き上げる
  （apap-engine側を下げるのではなく宿主側で上げる——apap-engineの下限要求は
  `json-schema-validator`の都合で決まっており、下げると別の不整合を招くため）。
- Jacksonのメジャー分裂（2.x対3.x）が起きた場合は互換方針の対象外であり、
  ADR-0017が「見直す条件」として挙げるShade導入（案A）の検討対象になる。

## 7. 永続化の選択肢と既定（In-Memory）を使った場合の性質

`ApapEngineBuilder()`は`repositories`引数を省略すると`ApapRepositories()`
（全14 Repository PortのIn-Memory実装、`apap.infrastructure.persistence.inmemory.*`）を使う。
この既定構成で`build()`した場合の性質:

- **プロセス再起動で全データ消失**: Provider/Model/Alias/Policy登録、Conversation履歴、
  Memory、Usage/Cost記録はすべてプロセスメモリ上のみに存在する。prompt-engineが複数Pod/
  複数インスタンスで動く場合、各インスタンスが独立した`ApapEngine`状態を持つ
  （Provider登録がインスタンス間で共有されない）。
- **`apap-infrastructure-jdbc`/`apap-infrastructure-distributed`は既定構成の依存グラフに
  一切現れない**（`EmbeddingConstraintTest`で機械検証。`ApapEngineBuilder`の
  `repositories`/`cacheStore`引数へ明示的にJDBC/Redis実装を注入しない限り、これらのモジュールは
  ロードされない）。
- 本番投入（Pod水平スケール、prompt-engine側は複数レプリカ運用が前提）では、
  `ApapEngineBuilder(repositories = ApapRepositories(providerRepository = JdbcProviderRepository(...), ...))`
  のように`apap-infrastructure-jdbc`の実装へ明示的に差し替える必要がある。この差替え自体は
  `apap-runtime`の依存には現れず、prompt-engine側の呼出コード（`ApapEngineConfig`）が
  `apap-infrastructure-jdbc`への依存を追加した上で行う（1章の「2つのみ」の対象外
  ——JDBC実装を使う場合は追加の依存宣言が必要になる、という制約として明記する）。
- `cacheStore`も同様に既定はIn-Memory（`InMemoryCacheStore`）。複数インスタンス間でCache
  Hit率を共有したい場合は分散KVS実装（`apap-infrastructure-distributed`）への差替えが必要
  （同上、追加依存が要る）。

## 8. 未確定のまま残る事項（この文書のスコープ外）

- 3章のStreamingブリッジ設計（どちらの選択肢を採るか）
- 2-bの`principal`/`tenantId`をリクエスト単位でどう受け渡すか（`ExecutionAdapter`シグネチャ
  変更の要否を含む、prompt-engine側の判断）
- Provider/Model/Policy登録の運用手順（`ApapEngine.admin`をどこから・どう呼ぶか。
  Admin API・起動時シーディング・別運用ツール等、prompt-engine側で選定）
- Plugin配置方針（`pluginDirectory(dir, trustedPublicKey)`の`dir`/署名鍵をprompt-engine側の
  デプロイ構成のどこに置くか）
- 6章のJacksonバージョンdiffの自動検知（現状は目視のみ、ADR-0017が課題として明記）
