# P11 総合検証レポート（P12 是正作業を反映）

実装全体を `docs/design/` に対して検証した結果と、その指摘に対する是正の記録。

- P11（検証）: 2026-09-03。**コードの追加より不足の発見を優先**し、
  問題を「設計の不備」（ADR起票）と「実装の不備」（修正タスク）に分けた
- P12（是正）: 2026-09-03。統合前の必須項目を解消し、性能を測り直した。
  **P11の性能数値は破棄し、3章を全面的に差し替えた**
- 検証者による自己評価ではなく、**テスト名と計測値を根拠**として記載する

## 0. この文書の読み方 —「動作している」と「検証されている」の区別

本プロジェクトでは「実装済みに見えるが機能していない」問題が繰り返し発生している
（CLAUDE.md 不変条件9に5件が列挙されている）。P11ではさらに**3件**を新たに発見した（F1〜F3）。
共通する原因は、単体テストが緑であることを「本番で動いている」ことの証拠として扱った点にある。

したがって本文書と `docs/traceability/requirements-matrix.md` では、次を厳密に区別する。

| 表現 | 意味 |
|---|---|
| **実装済** | 本番の配線（`ApapEngineBuilder.build()` または Gateway 起動）から到達する経路が存在し、その経路を通るテストが緑で、要件文をすべて満たす |
| **部分実装** | 実装は存在するが、上記のいずれかを欠く。欠けている条件を明記する |
| 「単体テストは緑」 | それだけでは実装済の根拠にならない。本番配線から到達しないなら部分実装 |

---

## 1. 要件マトリクスの完成（全95件）

`docs/traceability/requirements-matrix.md` の全95行について、判定基準を明文化したうえで
再分類した。従来使っていた「実装中」（48件）は、「Domain層だけ揃った」状態と
「E2Eで動く」状態を区別できず、まさに上記の問題を隠す表現だったため廃止した。

| 状態 | P11時点 | P12是正後 |
|---|---|---|
| 実装済 | 37 | **43** |
| 部分実装 | 46 | 41 |
| 未実装 | 9 | 8 |
| 対象外 | 3 | 3 |

P12で「実装済」へ移ったのは FR-PRV-006（健全性監視）/ FR-OBS-001・FR-SEC-006・FR-RTE-006（監査）/
FR-CAP-005（Tool Calling）/ NFR-PRF-001（付加レイテンシ）。いずれも**本番配線からの到達と
その経路を通るテスト**が揃ったことによる。

「対象外」の3件は、リポジトリ内の検査では充足を判定できないもの:

- **NFR-AVL-001**（稼働率99.95%/月）: 運用実績でのみ判定できる
- **FR-SEC-004**（転送時TLS 1.3・保存時暗号化）: Ingress/TLS終端・DBストレージ暗号化・外部Secret Storeの責務（ADR-0002）
- **NFR-SEC-002**（全通信TLS 1.3、内部mTLS）: サービスメッシュ/Ingress設定の責務

### マトリクス自体の健全性

マトリクスに記載された**すべてのテストクラス名が実在すること**を機械的に確認した（95行・欠落0）。
実装クラス名も同様に確認し、欠落は無い。「表にはあるがコードに無い」ドリフトは現時点で存在しない。

---

## 2. シーケンス図とテストの対応（05_シーケンス設計.md 全10本）

「E2E」は、`ApapEngineBuilder`（本番の組立入口）または実際のHTTP経由でその流れが通ることを指す。

| # | シーケンス | E2Eテスト | 単体テスト | 判定 |
|---|---|---|---|---|
| 5.1 | Chat（同期） | `CapabilitySmokeTest.chat capability returns a response and records both turns` / `ApapEngineBuilderTest.zero-dependency build works end to end for chat with adapter-mock` / `GatewayEndToEndTest` | 多数 | **あり**（ただしrole欠落によりSystem Prompt経路は未充足→F4） |
| 5.2 | Embedding | `CapabilitySmokeTest.embedding capability is deterministic and its response gets cached` / `ApapEngineBuilderTest` | — | **あり** |
| 5.3 | Streaming | `CapabilitySmokeTest.streaming chat capability returns chunks...` / `GatewayEndToEndTest.streaming chat emits 13-3 SSE events...` / `SseHeartbeatTest`（仮想時間） | `StreamingEngineTest`, `StreamingTurnRecorderTest` | **あり** |
| 5.4 | Tool Calling | **`ToolCallingE2ETest`**（P12で追加。往復2回・Streaming組立て・未終端のエラー化） | `ToolCallAssembler`関連 | **あり** |
| 5.5 | Function Calling | 同上（5.4と同一経路。13.2の`tools`/`tool_calls`を共有する） | 同上 | **あり** |
| 5.6 | Fallback | **`SequenceFlowE2ETest`**（P12で追加。1つ目の候補が失敗したとき2つ目が実際に呼ばれる） | `FallbackEngineTest`（6ケース） | **あり** |
| 5.7 | Retry | `ApapEngineBuilderTest.injected retryStrategy drives the retry - a delay retries, null does not` | `AttemptExecutorTest`（分類ごとのリトライ可否8ケース、`Retry-After`尊重、Span出力） | **あり** |
| 5.8 | Provider切替（Alias/Canary） | **`SequenceFlowE2ETest`**（P12で追加。Alias切替後に実行先Providerが変わる） | `RoutingEngineTest`, `CanaryResolutionServiceTest` | **あり**（Canary比率のAdmin API公開口は未実装→F8） |
| 5.9 | Capability Discovery | `GatewayEndToEndTest`（`GET /v1/capabilities`） | `CapabilityDiscoveryQueryTest`（テナントPolicyによる除外） | **あり**（ただし登録側の`CapabilityRegistry`は未配線→F3） |
| 5.10 | Health Check | **`SequenceFlowE2ETest`**（P12で追加。周期タスク1周で`ProviderHealthChanged`が発火し`/health/providers`へ届く） | `HealthCheckServiceTest`, `ProviderHealthAggregatorTest` | **あり** |

### 欠落の要約（P12で解消）

P11時点で欠落していた5本（5.4 / 5.5 / 5.6 / 5.8 / 5.10）はP12ですべて追加した。
**10本すべてにE2Eテストが存在する。**

Tool Calling（5.4後半）の`tool_results`往復は、P11時点では**それを表現する型が
リポジトリのどこにも存在しなかった**。P12で `ToolResult` をドメイン・SPI・公開API・
Gateway DTO に追加し、往復2回が閉じることをE2Eで確認している。

---

## 3. 性能実測

> **P12（是正作業）で全面的に測り直した。** P11の数値は、レート制限の既定値が
> 全リクエストを毎秒1件へ絞っていた状態（P11-F10 / ADR-0035）で測ったものなので破棄する。

### 3.1 P11報告の訂正: 何によって測ったのか

P11では「`apap_overhead_duration_seconds`は要件の区間を覆っていない」と書きながら
NFR-PRF-001を「満たす」と判定しており、報告として紛らわしかった。事実関係は次のとおり。

- **P11の数値はメトリクス経由ではない。** `PerformanceBenchmark`が
  `System.nanoTime()`を2点で採取した独立計測である。
  始点はKtorの`ApplicationCallPipeline.Setup`（パイプライン最初のフェーズ。
  認証・JSON解析・DTO変換より前）、終点はAdapterの`execute`入口を包むデコレータ。
  したがって**測定区間は要件の定義区間（Gateway受信〜Adapter送信）と一致している**。
- 一方で「メトリクスがその区間を覆っていない」も同時に正しかった。両者は別の話であり、
  P11の判定自体は誤りではないが、区別を明示していなかった。
- **測定区間の限界**: Nettyがソケットから読み出してパイプラインへ渡すまでの時間は
  始点より前にあるため含まれない。つまりこの計測は真の値より**やや短く出る**。

### 3.2 P12でメトリクスの計測点を実装した（ADR-0034）

要件の区間をメトリクスでも監視できるようにした。

| phase | 記録者 | 区間 |
|---|---|---|
| `gateway` | Gateway（`finishGatewayPhase`） | 受信〜`ApapEngine`呼び出し直前（認証・JSON解析・Idempotency・DTO変換） |
| `prompt` / `cache-lookup` / `routing` / `context` / `token-estimate` | `ExecutionEngine` | エンジン内部の各段 |
| `dispatch` | `AttemptExecutor` | attempt開始〜**Adapter送信直前**（CB取得・レート制限・Provider/Model解決・RequestMapper） |
| `mapping` | `ExecutionEngine` | 応答の正規化（復路） |

`execution` phaseは**メトリクスへ記録しない**ようにした。この区間はProvider呼び出しを
内包しており、APAPの付加分ではないため（Spanとしては残す）。
2.19が挙げる4ラベル `gateway` / `prompt` / `routing` / `mapping` はすべて記録される。

`OverheadPhaseCoverageTest`が、(a) 必要なphaseが実際に記録されること、
(b) Provider遅延を400ms増やしてもoverhead合計が比例して増えないこと（＝Provider時間の非混入）を
機械検証する。**絶対値の閾値では判定できない**——初回リクエストはJITで1秒を超えることがあり、
warmupノイズに埋もれる（最初そう書いて誤検知した）ため、差分で見ている。

### 3.3 計測条件

| 項目 | 値 |
|---|---|
| マシン | Apple M3 / 8コア / メモリ 8GB / macOS 26.3.1 |
| JVM | OpenJDK 21.0.12.1 (Homebrew), 64-Bit Server VM, 既定ヒープ |
| 実行 | `./gradlew :gateway:apap-gateway:test --tests '*PerformanceBenchmark*' -Dapap.benchmark=true` |
| サーバ | 実 `embeddedServer(Netty)`（`testApplication`ハーネスではない） |
| クライアント | Ktor `HttpClient`、**同一JVM内**、loopback |
| Provider | `adapter-mock`（遅延ゼロ）、rpm=6,000,000（レート制限を測定対象から外すため） |
| ウォームアップ | 非Streaming 500回 / Streaming 200回 |
| 試行回数 | PRF-001: 2,000（逐次） / PRF-002: 1,000（逐次） / PRF-003: 10秒 |

### 3.4 測定結果

| 指標 | p50 | p90 | p99 | max | min | n |
|---|---|---|---|---|---|---|
| **NFR-PRF-001** Gateway受信→Adapter送信 | **0.335ms** | 0.776ms | **2.295ms** | 294.2ms | 0.143ms | 2,000 |
| **NFR-PRF-002** Gateway受信→初回`content_delta` | **5.238ms** | 12.526ms | **49.853ms** | 556.8ms | 2.192ms | 1,000 |

| 指標 | 実測 |
|---|---|
| **NFR-PRF-003** スループット（並列度64、10秒） | **605.3 req/s**（成功6,076件） |

### 3.5 要件充足の判定

| 要件 | 目標 | 実測 | 判定 |
|---|---|---|---|
| NFR-PRF-001 | p50 ≤ 15ms / p99 ≤ 50ms | p50 **0.335ms** / p99 **2.295ms** | **満たす**（区間一致を3.1で確認済み。メトリクスでも監視可能になった） |
| NFR-PRF-002 | ≤ 30ms | p50 5.238ms / p90 12.526ms / p99 **49.853ms** | **p50・p90では満たす。p99では満たさない**（要件文に分位点の指定が無い。p99基準なら未達） |
| NFR-PRF-003 | 1,000 req/s / ノード | **605.3 req/s** | **この計測環境では判定不能**（3.6参照） |

### 3.6 NFR-PRF-003 のボトルネック切り分け（P12 作業2）

「227 req/s（P11）／605 req/s（P12）」という数値だけでは対処できないため、
同一クライアント・同一並列度で3通りを測って切り分けた。

**(1) 負荷生成側が飽和していないかの確認** — エンジンを通さない素のKtorルート（`/ping`）を
同じ条件で叩き、ハーネス自体の上限を測った。

| 並列度 | ハーネス上限（エンジンなし） | エンジン経由 |
|---|---|---|
| 1 | 161.9 req/s | 67.7 req/s |
| 8 | 303.8 req/s | 170.4 req/s |
| 64 | 403.5 req/s | 318.1 req/s |

**ハーネス自体が 400 req/s 程度で頭打ちしている。** 別途（サンプラを止めた状態で）
測ったエンジン経由の値は 605 req/s で、この「ハーネス上限」を上回っている。
つまりこの数値は環境ノイズに強く影響される程度の差しかなく、
**クライアントとサーバが同一の8コアJVMを奪い合っている構成では、
1,000 req/s に到達できるかどうかを判定できない。** 負荷生成側が先に飽和する。

**(2) ウォームアップ・JIT・GCの除外** — 各計測の前に500回（Streamingは200回）の
ウォームアップを行い、測定は分布で示している。max値（294ms / 556ms）はGCとJITの影響を含む。

**(3) プロファイリング** — 負荷中に20ms間隔でスレッド状態を採取した（簡易サンプラ）。

| 並列度 | スレッド状態 | 頻出フレーム（上位） |
|---|---|---|
| 1 | TIMED_WAITING=21125, RUNNABLE=4596, BLOCKED=0 | `ExecutionRoutes`, `DefaultQuotaManager.expireStale`, `AuditEngine.enqueue` |
| 8 | TIMED_WAITING=18289, RUNNABLE=5648, **BLOCKED=93** | `DefaultQuotaManager.checkAndReserve`, `commit`, `CircuitBreaker.tryAcquire` |
| 64 | TIMED_WAITING=12775, RUNNABLE=9319, **BLOCKED=1046** | `DefaultQuotaManager.checkAndReserve`(162), `commit`(82), `CircuitBreaker.tryAcquire`(55), `recordSuccess`(35) |

BLOCKEDスレッドが並列度とともに増え（0 → 93 → 1046）、その頻出フレームは
`DefaultQuotaManager` と `CircuitBreaker` の共有状態操作に集中している。
**ロック競合は存在する。**

**結論: ボトルネックは未特定。**

- ハーネスがエンジンと同程度（400〜600 req/s）で飽和しているため、
  **エンジンの上限を測れていない**。1,000 req/s に対する不足が実装由来かどうかを
  この環境では判定できない。
- `DefaultQuotaManager` / `CircuitBreaker` のロック競合は観測されたが、
  BLOCKEDは全サンプルの約4.5%（1046 / 23140）に留まり、
  **これが律速だと結論づける根拠は無い**。サンプラはクライアント側スレッドも数えている。
- 判定には**クライアントとサーバを別マシンへ分離した再計測**が必要。それまでは
  「605 req/s は本構成での観測値であって、APAPの上限ではない」とだけ言える。

### 3.7 埋込利用（低流量）への影響

prompt-engineのような埋込利用では、レート制限が明示設定されない限り絞りは掛からず
（ADR-0035）、付加レイテンシは p99 で 2.3ms である。低流量ではスループット上限が
問題になる可能性は低い。ただし**既知の上限として `docs/integration/prompt-engine.md` に
記載する**（同一プロセス内での上限は未計測であり、605 req/s はHTTP経由の観測値）。

### 3.8 測定の限界（必読）

1. **クライアントとサーバが同一JVM・同一マシン**。3.6のとおり、これがスループット計測の
   律速になっている。
2. Providerは遅延ゼロのモックで、実ProviderのI/O待ち中に他リクエストを処理できる
   並行性の効果を再現していない。実構成ではスループットが上がる可能性が高い。
3. メモリ8GBの開発機であり、本番想定のノードスペックではない。
4. NFR-PRF-004（Cache Hit p99 ≤ 20ms）と NFR-PRF-005（同時Streaming 10,000接続）は
   **未計測**のまま。後者は単一マシンでは検証できない。

## 4. セキュリティ確認

### 4.1 Credential文字列の非露出（新規テストを追加）

`gateway/apap-gateway/src/test/kotlin/apap/gateway/CredentialLeakageTest.kt` を追加した。
**静的な目視ではなく実行時に**、見張り文字列（sentinel）を正規のCredential経路
（`SecretAccessor` と Authorization ヘッダ）へ流し込み、出口を実際に読み取って検査する。

| 出口 | 検査方法 |
|---|---|
| HTTP応答ボディ | 成功・SSE・認証失敗・Provider失敗（`AUTH_ERROR`強制）の4系統を収集 |
| HTTP応答ヘッダ | 全ヘッダ値を文字列化して検査 |
| メトリクスラベル | `/metrics`（OpenMetrics本文）を取得して検査 |
| ログ | logback `ListAppender` を root に取り付け、全レベルのメッセージ・引数・例外スタックトレースを収集 |
| Audit本文 | **Gateway経由では観測不能**（`AuditEngine`が本番未配線＝F1のため）。`apap-runtime`の`CapabilitySmokeTest`ハーネスが明示配線した状態でAuditRecordを検証している |

ログの検査には SLF4J のバインディングが必須である（バインディング不在ではログが破棄され、
「漏れていない」と誤判定する）。このため `logback-classic` を **Gatewayのテスト専用依存**として
追加した（本番のログ実装は埋込先が選ぶ、というCLAUDE.md 不変条件6の方針は変えていない）。

収集経路が壊れていても緑にならないよう、テストは検査本体の前に
「収集した出力が一定量あること」「プローブのログ行が含まれること」「応答ボディが含まれること」を
先にアサートする。

### 4.2 Vendor Neutrality の走査範囲

`VendorNeutralityTest` の走査ルートが `modules` / `gateway` / `adapters` のみで、
**`integration` が対象外**だった。修正済み。詳細は §5.1。

### 4.3 テナント分離の検査状況

`TenantScopedRepositoryTest` は Port のシグネチャに `TenantId` があることを機械検証していたが、
**対象ファイル名を手で書き並べる方式**だったため、新しいテナント境界付きPortが黙って
対象外になる構造だった（`scannedRoots` の漏れと同型）。P11で以下を修正した。

- 検証対象に `UsageRepository.aggregate` を追加（対象からも除外からも漏れていた）
- **網羅性検査を追加**: `TenantId` に言及する Port ファイルは、検証対象か理由付き除外かの
  どちらかであることを強制する。除外は `MetricsRecorder` / `PolicyRepository` /
  `SessionRepository` の3件で、それぞれ理由を記述（理由が空なら失敗する）
- 取り残し検査: 除外に書かれたファイルが実在すること、`TenantId` に言及しなくなった
  ファイルが対象/除外に残っていないこと

この網羅性検査により、**`AuditRepository` の欠落**を発見した。`AuditSearchCriteria.tenantId` が
nullable かつ既定 null で、`search(AuditSearchCriteria())` が全テナントの監査ログを返す。
Port契約がテナント横断の読み出しを許している（ADR-0033 / F9）。

現時点で悪用可能な経路は無い（`AuditEngine` 未配線でログが記録されず、
`GET /admin/v1/audit` は NOT_IMPLEMENTED）。しかし配線した時点で境界の無いAPIが
自然にできあがるため、実装前に契約を直す。

### 4.4 違反注入による検査の確認（不変条件9）

§5 の表にまとめて記載。

---

## 5. 発見した問題

### 5.1 着手前作業で発見・修正済み: スコープ検査の対象範囲の漏れ

不変条件9（違反を注入して落ちることを確認する）では、**検査の対象範囲そのものの漏れは捕まらない**。
注入先が対象モジュール内であれば正しく落ちるため、注入者は「検査は機能している」と結論してしまう。

走査対象をパラメータに持つ検査を全て列挙し、`settings.gradle.kts` のモジュール一覧と突き合わせた。

**リポジトリ全体を走査する検査（3件）**

| 検査 | 修正前の走査ルート | 漏れ | 修正後 |
|---|---|---|---|
| `VendorNeutralityTest` | modules, gateway, adapters | **integration** | 4ルート全て |
| `ClockAndIdGeneratorDirectCallTest` | modules, gateway | **adapters, integration** | 4ルート全て |
| `TestMethodReturnTypeTest` | modules, gateway, adapters | **integration** | 4ルート全て |

**モジュール単位で走査する検査（設計上その1モジュールのみが対象。漏れではない）**

`DomainDependencyRuleTest` / `DomainPortInterfaceRuleTest` / `AdapterDependencyRuleTest` /
`ProviderDependencyRuleTest` / `RoutingDependencyRuleTest` / `SpiSurfaceTest` /
`EmbeddingConstraintTest` / `ManagerStateMutationCoverageTest` / `MetricsCoverageTest` /
`DomainEventCoverageTest` / `TenantScopedRepositoryTest` / `OpenApiContractTest` /
`EndpointCatalogTest` / `DocumentedSnippetTest` / `HostCompileClasspathTest`

**再発防止の仕組み**

`ModuleScanCoverage.assertScanCoversAllModules(checkName, repoRoot, scannedRoots, exclusions)` を
追加し、リポジトリ全体を走査する検査は検証本体の前にこれを呼ぶ。走査ルートは検査側の変数を
そのまま渡す（中央の登録簿へ値を書き写すと、写した側の更新漏れという同じ問題を再生産するため）。

- モジュール一覧の唯一の情報源は `settings.gradle.kts`。パーサが1件も読めなければ失敗する
- 除外は `ScanExclusion(modulePath, reason)` で**理由必須**。空理由は失敗する
- 存在しないモジュールを指す古い除外も失敗する
- `RepoWideScanRegistrationTest` が「`scannedRoots` を宣言するテストは必ず
  `assertScanCoversAllModules` を呼ぶ」ことを検証し、分散方式の抜け道を塞ぐ
- `ModuleScanCoverageTest`（8ケース）が除外まわりの全分岐を人工の `settings.gradle.kts` で実行する。
  実リポジトリでは除外を1件も使っておらず、**使われていない逃げ道は壊れても気づけない**ため

### 5.2 設計の不備（ADR起票）

| # | 内容 | 影響する要件 | ADR |
|---|---|---|---|
| **D1** | 13.2 のリクエストは `messages[].role` を持つが、3.3.1 の `CanonicalRequest.input` は `ContentPart[]` で **roleを持たない**。また Template を指す参照フィールドも無い | FR-CAP-001（System Prompt）, FR-PMT-004 | [ADR-0031](adr/ADR-0031-canonical-request-loses-role-and-template-reference.md) |
| **D2** | FR-EXE-006 が必須とする Scheduler の**実行主体が未定義**。埋込ライブラリが常駐スレッドを持たない方針（不変条件6）と衝突し、5.10 が要求するリーダー選出の置き場所も決まっていない | FR-EXE-006, FR-PRV-006, FR-CAP-016, FR-SEC-002, NFR-SEC-003, NFR-DAT-001 | [ADR-0032](adr/ADR-0032-scheduler-execution-host.md) |
| **D3** | 監査ログ検索のテナント境界が未定義。`AuditSearchCriteria.tenantId` が nullable でテナント横断読み出しを許す | FR-SEC-003, FR-SEC-006 | [ADR-0033](adr/ADR-0033-audit-search-requires-tenant-scope.md) |
| **D4** | NFR-PRF-001 の計測区間に対応する計測点が 2.19 にも実装にも無い（`phase=gateway` ラベルだけが定義されている） | NFR-PRF-001, NFR-PRF-002, NFR-OBS-002 | [ADR-0034](adr/ADR-0034-gateway-phase-overhead-measurement-point.md)（P12で実装済み） |
| **D5** | **テナント別レート制限に設定元が無い**。設計書はProviderの`rate_limits`しか定義せず、テナント側の上限値をどのエンティティが持つか未定義。既定バケットがそのまま適用され、意図しない全体スロットルになっていた | FR-EXE-003, NFR-PRF-003 | [ADR-0035](adr/ADR-0035-tenant-rate-limit-has-no-source-of-truth.md) |

### 5.3 実装の不備（P12での対応状況）

「本番配線から生成されないクラス」を機械的に洗い出し、KDocだけの言及と実際の構築を区別して確認した。

| # | 内容 | 影響する要件 | P12での状態 |
|---|---|---|---|
| **F1** | **`AuditEngine`が本番配線に存在せず、監査ログが1件も記録されない**。単体テストと`CapabilitySmokeTest`は**テストハーネスが明示的に配線**して緑になっていた | FR-OBS-001, FR-SEC-006, FR-SEC-007, FR-RTE-006 | **解消**。`ApapRepositories`に`AuditRepository`を追加し、`ApapEngineBuilder.build()`で`AuditEngine`を構築。`close()`は書き込み完了を待ってからスレッドを止める（停止直前の監査記録を失わないため）。`ProductionWiringTest`が検証 |
| **F2** | **Provider健全性の周期監視が起動されない**。`ProviderHealthAggregator`が構築されず、Schedulerも無いため`ProviderHealthChanged`が一度も発火しない | FR-PRV-006, FR-OBS-006 | **解消**。`ProviderHealthCheckTask`（30秒周期、5.10準拠）を新設し`ApapEngine.scheduledTasks`で公開、`ProviderHealthAggregator`を`/health/providers`へ配線。`SequenceFlowE2ETest`が検証 |
| **F3** | **`CapabilityRegistry`が本番配線に存在しない**。参照側は到達可能だが、スキーマ登録側が機能しない | FR-CAP-017, NFR-EXT-003 | **未解消**（統合をブロックしない。Capability定義の追加は現状Adapter申告とModel登録で足りる） |
| **F4** | **Gatewayが`messages[].role`を黙って捨てる**。System PromptとUser Promptを区別できない | FR-CAP-001 | **未解消**（ADR-0031。ドメインの`CanonicalRequest`にroleが無く、実装だけでは決められない） |
| **F5** | `SessionManager`が本番配線に存在しない | FR-CTX-001 | **未解消**（`/v1/sessions`はNOT_IMPLEMENTEDとして明示済み） |
| **F6** | Gatewayの本番起動がIn-Memory既定のまま。複数Podでの水平スケールが成立しない | NFR-AVL-003, NFR-EXT-005 | **未解消** |
| **F7** | **Tool Calling経路のテストが1件も無い**。往路・復路とも配線されているが動作は未検証。`tool_results`を表現する型も存在しない | FR-CAP-005 | **解消**。`ToolResult`をドメイン→SPI→公開API→Gateway DTOへ追加し、`ToolCallingE2ETest`で往復2回・Streaming組立て（文字列内ブレース／エスケープ引用符／複数call並行）・未終端のエラー化を検証 |
| **F8** | Adminの公開口が複数欠落（Credential rotation、Canary比率、analytics、plugins scan、quotas/budgets、cache invalidate） | FR-PRV-005, FR-MDL-004, FR-OBS-004, NFR-EXT-001 ほか | **未解消**（`EndpointCatalog`が理由付きで明示済み。26エンドポイントがNOT_IMPLEMENTED） |
| **F9** | `AuditRepository.search`がテナント横断の読み出しを許す | FR-SEC-003 | **未解消**（ADR-0033。`GET /admin/v1/audit`自体がNOT_IMPLEMENTEDのため現時点で悪用経路は無い） |
| **F10** | **`RateLimiter.configure()`が本番配線から一度も呼ばれず、Providerの`rateLimits`が反映されない**。全スコープが既定（容量60・毎秒1補充）で動作し、**出荷時のスループット上限が毎秒1リクエスト**だった | FR-EXE-003, NFR-PRF-003 | **解消**。`ProviderRateLimitConfigurer`が起動時と`ProviderRegistered`受信時に`rpm`をバケットへ反映。併せて未設定スコープの既定を実質無制限へ変更（ADR-0035）。`ProductionWiringTest`が「rpm=60のProviderに63件流すと補充待ちが観測される」ことで検証 |
| **F11** | **`MetricsEngine`が二重に購読され、イベント起因のメトリクスが全て2倍**だった | FR-OBS-002, NFR-OBS-002 | **解消**（P11で修正済み。`MetricsSubscriptionTest`が検証） |
| **F12** | **`tpm`（トークン/分）と`concurrent`（同時実行数）がレート制限へ反映されない**。トークンバケットでは表現できず、`rpm`のみ反映される | FR-EXE-003 | **未解消**（ADR-0035の「未決定のまま残る事項」） |

#### F10の調査で判明した、より根本的な問題

当初の診断（`configure()`が呼ばれない）は正しかったが、**律速はテナントスコープだった**。
`AttemptExecutor`はテナント→Providerの順にトークンを取得するが、テナント側には
**ドメイン上の設定元が存在しない**（設計書はProviderの`rate_limits`しか定義していない）。
そのため既定バケットがそのまま適用され、Providerのrpmを何に設定しても
毎秒1リクエストで頭打ちになっていた。ADR-0035として起票し、
「根拠の無いスコープは絞らない」方針へ変更した。

### 5.4 不変条件9: 違反注入による検査の確認

新規追加・変更したすべての検査について、意図的な違反を注入して落ちることを確認し、その後撤去した。

**P11で追加した検査**

| 検査 | 注入した違反 | 結果 |
|---|---|---|
| `ModuleScanCoverage`（3検査から呼ばれる） | `settings.gradle.kts` に `probe:scope-probe` を仮追加 | **3検査すべて失敗**。漏れているモジュール名と対処方法を明示 |
| `RepoWideScanRegistrationTest` | `scannedRoots` を宣言し登録しないテストを追加 | **失敗**。該当ファイルを名指し |
| `CredentialLeakageTest` | `GatewayServer`にBearerトークンをWARNログへ出力する行を挿入 | **失敗**。`Credentialの実値が出力へ混入しました: [CANARY-BEARER-...]` |
| `MetricsSubscriptionTest` | `ApapEngineBuilder`に`MetricsEngine`の重複構築を復元 | **失敗**。`apap_cache_events_total が 2 です`（期待1） |
| `TenantScopedRepositoryTest`（網羅性検査） | — | 追加時点で`AuditRepository`の欠落を**実際に検出**（注入不要で実欠陥を発見） |

**P12で追加した検査**

| 検査 | 注入した違反 | 結果 |
|---|---|---|
| `OverheadPhaseCoverageTest` | `execution` phaseをoverheadメトリクスへ記録し直す（`recordOverhead = false`を外す） | **失敗**。`execution phaseがoverheadへ記録されています。この区間はProvider呼び出しを内包するため…` |
| `ProductionWiringTest`（F1 監査） | `AuditEngine`の購読先を別のEvent Busへ差し替え | **失敗**。`監査ログが1件も記録されていません` |
| `ProductionWiringTest`（F10 レート制限） | `ProviderRateLimitConfigurer`の適用を外す | **失敗**。`rpm=60 のProviderに63件流したのに137msで完了しました` |
| `ProductionWiringTest`（D2 周期タスク） | `scheduledTasks`を空リストにする | **失敗**。`Providerの健全性監視タスクが公開されていません（[]）` |

`ToolCallingE2ETest` / `SequenceFlowE2ETest` は既存の欠落（テストが無い）を埋めるものなので、
注入ではなく**追加した時点で対象機能が動くことを実測**している（未終端tool callの
エラー化、Fallback時の2つ目の候補呼び出し、健全性変化の`/health/providers`到達）。

## 6. prompt-engine への統合を開始してよいか（P12 是正後の判断）

### 判断: **開始してよい。** P11で挙げた必須3件は解消し、追加された必須項目（Tool Calling）も解消した。

### 6.1 P11で「開始前に必須」とした項目の状態

| # | 項目 | 状態 | 根拠 |
|---|---|---|---|
| 1 | F10: レート制限の既定値（毎秒1リクエスト） | **解消** | `ProviderRateLimitConfigurer`がProviderの`rpm`を反映。未設定スコープは絞らない（ADR-0035）。`ProductionWiringTest`が絞りの実効性を実測で検証 |
| 2 | F1: AuditEngine未配線 | **解消** | `ApapEngineBuilder.build()`で構築・購読。`close()`は書き込み完了を待つ。`ProductionWiringTest`がAuditRecordの実在を検証 |
| 3 | D2/F2: 周期実行 | **解消** | `ScheduledTask` Portで公開（ADR-0032）。Gatewayは`ScheduledTaskRunner`で駆動し、停止時に確実に止める。埋込ホストは`engine.scheduledTasks`を自分のスケジューラで回す |
| 4 | Tool Calling / Function Calling（P12で追加された必須項目） | **解消** | `ToolResult`型を追加して往復を成立させ、`ToolCallingE2ETest`で往復2回・Streaming組立て・未終端のエラー化を検証 |

### 6.2 統合してよいと判断する根拠

1. **ホスト互換性が機械検証されている**。`integration/host-compat`が、prompt-engineが実際に持つ
   依存（`apap-runtime` + `apap-api`のみ）でコンパイルできることを検証し、
   統合ドキュメントのコード例は実コードから生成される（ADR-0029）。
2. **10本のシーケンスすべてにE2Eテストがある**（2章）。P11で欠落していた5本はP12で追加した。
3. **本番の入口を通した配線検証がある**。`ProductionWiringTest`は`ApapEngineBuilder`経由でしか
   検出できない不具合（監査・レート制限・周期タスク）を対象にしている。
   P11で見つかった3件はいずれも「単体テストは緑・本番では動かない」型だった。
4. **付加レイテンシは要件を大きく下回る**（p50 0.335ms / p99 2.295ms、目標15ms/50ms）。
   計測区間が要件の定義区間と一致していることも3.1で確認済み。

### 6.3 統合時に必ず伝えるべきこと（ドキュメント必須事項）

コードでは解決しておらず、**知らないと誤解する**もの。
`docs/integration/prompt-engine.md` に記載する。

| 項目 | 内容 |
|---|---|
| 周期タスクの駆動 | `engine.scheduledTasks`を宿主が回さない限り、**Providerの健全性監視は動かない**（`/health/providers`は初期値のまま、Routingのヘルスフィルタも効かない） |
| レート制限 | テナント別制限は既定で**掛からない**（ADR-0035）。絞りたい場合は`ApapEngineBuilder.rateLimits(...)`で明示すること。`tpm`/`concurrent`は未反映（F12） |
| 監査ログの保存先 | 既定はIn-Memoryで**プロセス再起動で消える**。監査要件を満たすには`JdbcAuditRepository`へ差し替える |
| 永続化 | Provider/Model登録も既定はIn-Memory。再起動で構成が失われる |
| スループット | 605 req/s は同一JVM上のHTTP経由での観測値であり、**上限ではない**（3.6）。埋込利用（同一プロセス内呼び出し）の上限は未計測 |
| System Prompt | `messages[].role`は現状**受け取っても効かない**（F4 / ADR-0031）。System Promptを使う機能はADR-0031の実装を待つこと |

### 6.4 統合後に着手すべき項目（開始をブロックしない）

- **F4/D1（role欠落）**: System Promptを使う機能を計画しているなら、それより前にADR-0031の実装が必要
- **F3（CapabilityRegistry未配線）**: 新Capabilityをスキーマ登録だけで追加したくなった時点で必要
- **F6（GatewayがIn-Memory既定）**: 埋込利用では直接影響しないが、Gatewayを本番運用する際に必要
- **F8（Admin公開口の欠落）**: Credential rotation・Canary比率・analyticsを使う時点で必要
- **NFR-PRF-003の再計測**: クライアントとサーバを別マシンへ分離した環境が必要（3.6）
- **NFR-PRF-002のp99**（49.9ms > 30ms）: 分位点の基準を確定したうえで改善要否を判断する

## 7. デプロイ時に別途確認すべき事項（リポジトリ外）

「対象外」と判定した要件は、リポジトリ内の検査では充足を示せない。デプロイ設計時に確認すること。

- **FR-SEC-004 / NFR-SEC-002**: TLS 1.3 の終端設定、内部通信の mTLS、DBストレージ暗号化、
  外部 Secret Store の保存時暗号化（ADR-0002 で外部委譲と決定済み）
- **NFR-AVL-001**: 稼働率 99.95%/月 の実測。SLO 監視（NFR-OBS-004）が未実装のため現状は測れない
- **NFR-AVL-004**: `terminationGracePeriodSeconds` を **300秒より大きく**設定すること
  （Streaming の排出上限が300秒。Dockerfile にも記載）
- **NFR-EXT-005**: HPA の設定とスケール試験
- **ADR-0033 の実装時**: `GET /admin/v1/audit` のテナントIDは認証済みトークンのクレームから取り、
  クエリパラメータで上書きできないようにすること
