# P11 総合検証レポート

実装全体を `docs/design/` に対して検証した結果。**コードの追加より不足の発見を優先**し、
発見した問題を「設計の不備」（ADR起票）と「実装の不備」（修正タスク）に分けて記録する。

- 検証日: 2026-09-03
- 対象コミット: `f31b445` 時点 + 本タスクの変更
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

| 状態 | 件数 |
|---|---|
| 実装済 | 37 |
| 部分実装 | 46 |
| 未実装 | 9 |
| 対象外 | 3 |

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
| 5.4 | Tool Calling | **なし** | `StreamingEngineTest`（tool_call_deltaチャンクの正規化のみ） | **欠落** → F7 |
| 5.5 | Function Calling | **なし** | 同上 | **欠落** → F7 |
| 5.6 | Fallback | **なし** | `FallbackEngineTest`（6ケース: 次候補への移行、非fallback対象での停止、CB Open候補のスキップ、予算不足での停止 等） | **E2E欠落**（単体は充実） |
| 5.7 | Retry | `ApapEngineBuilderTest.injected retryStrategy drives the retry - a delay retries, null does not` | `AttemptExecutorTest`（分類ごとのリトライ可否8ケース、`Retry-After`尊重、Span出力） | **あり** |
| 5.8 | Provider切替（Alias/Canary） | **なし** | `RoutingEngineTest`（5.8シナリオ）, `CanaryResolutionServiceTest`, `ModelManagerTest` | **E2E欠落**（Gatewayに Canary 設定口が無い→F8） |
| 5.9 | Capability Discovery | `GatewayEndToEndTest`（`GET /v1/capabilities`） | `CapabilityDiscoveryQueryTest`（テナントPolicyによる除外） | **あり**（ただし登録側の`CapabilityRegistry`は未配線→F3） |
| 5.10 | Health Check | **なし** | `HealthCheckServiceTest`, `ProviderHealthAggregatorTest` | **欠落**（Schedulerが無く周期実行されない→D2/F2） |

### 欠落の要約

- **完全に欠落**: 5.4 / 5.5（Tool Calling・Function Calling）、5.10（Health Check）
- **E2Eのみ欠落**（単体テストはある）: 5.6（Fallback）、5.8（Provider切替）

5.4/5.5 は「往路（`tools`→Adapter）と復路（`toolCalls`→13.2 DTO）は配線されているが、
それを通るテストが1件も無い」状態であり、**動作しているかどうか不明**である。
加えて 5.4 後半の tool_results 往復は、それを表現する型がリポジトリのどこにも存在しない。

---

## 3. 性能実測

### 3.1 前提の確認: `apap_overhead_duration_seconds` は計測区間を覆っていない

計測前に指示された確認の結果、**覆っていない**。

NFR-PRF-001 は計測区間を「Gateway受信〜Adapter送信」と定義し、02_システム仕様.md 2.19 も
`apap_overhead_duration_seconds` の `phase` ラベルに `gateway` を挙げている。しかし実装では
このメトリクスを記録する `PhaseTimings` が `ExecutionEngine.execute` の**内側にしか無く**、
実際に記録される phase は `prompt` / `routing` / `context` / `token-estimate` /
`cache-lookup` / `execution` / `mapping` の7種で、`gateway` は**一度も記録されない**。

- Gateway層（トークン検証・JSON解析・Idempotency判定・DTO変換）は計測対象外
- `execution` phase は Provider 呼び出しそのものを含むため、7 phase の合計は「付加分」にならない

→ **NFR-PRF-001 が定義する区間を計測するメトリクスは存在しない**。設計書にも計測点の定義が
無いため ADR-0034 として起票した。本節の測定はメトリクス経由ではなく、
`PerformanceBenchmark` が Gateway 受信時刻と Adapter 到達時刻を直接採取して行っている。

### 3.2 計測条件

| 項目 | 値 |
|---|---|
| マシン | Apple M3 / 8コア / メモリ 8GB / macOS 26.3.1 |
| JVM | OpenJDK 21.0.12.1 (Homebrew), 64-Bit Server VM, 既定ヒープ（`-Xmx`未指定） |
| 実行方法 | `./gradlew :gateway:apap-gateway:test --tests '*PerformanceBenchmark*' -Dapap.benchmark=true` |
| サーバ | 実 `embeddedServer(Netty)`（`testApplication`ハーネスではない） |
| クライアント | Ktor `HttpClient`、**同一JVM内**、loopback接続 |
| Provider | `adapter-mock`（遅延ゼロ） |
| ウォームアップ | 非Streaming 500回 / Streaming 200回（JIT・接続確立・クラスロードを測定から除外） |
| 試行回数 | NFR-PRF-001: 2,000回（逐次） / NFR-PRF-002: 1,000回（逐次） / NFR-PRF-003: 10秒間・並列度64 |
| Rate Limiter | **上限を実質無制限に設定**（下記3.4の理由）。既定構成での値は別途測定 |

### 3.3 測定結果（分布）

| 指標 | p50 | p90 | p99 | max | min | n |
|---|---|---|---|---|---|---|
| **NFR-PRF-001** APAP付加レイテンシ（Gateway受信→Adapter送信） | **0.836ms** | 2.356ms | **11.497ms** | 376.067ms | 0.245ms | 2,000 |
| **NFR-PRF-002** Streaming初回チャンク付加遅延（Gateway受信→初回`content_delta`） | **4.247ms** | 11.615ms | **50.198ms** | 761.723ms | 1.828ms | 1,000 |

| 指標 | 実測 | 条件 |
|---|---|---|
| **NFR-PRF-003** スループット | **227.0 req/s** | レート制限を外した状態、並列度64、10秒間で3,018件成功 |
| （参考）出荷時の既定構成でのスループット | **4.7 req/s** | 初期バースト60件を含む10秒間で68件。**定常状態は毎秒1件**（既定の補充レート） |

### 3.4 要件充足の判定

| 要件 | 目標 | 実測 | 判定 |
|---|---|---|---|
| NFR-PRF-001 | p50 ≤ 15ms / p99 ≤ 50ms | p50 **0.836ms** / p99 **11.497ms** | **満たす** |
| NFR-PRF-002 | ≤ 30ms | p50 **4.247ms** / p90 11.615ms / p99 **50.198ms** | **p50・p90では満たすが、p99では満たさない**（要件文に分位点の指定が無い。p99基準なら未達） |
| NFR-PRF-003 | 1,000 req/s / ノード | **227.0 req/s**（制限解除時）／**4.7 req/s**（出荷時既定） | **満たさない** |

NFR-PRF-003 が出荷時既定で 4.7 req/s に留まるのは性能上の限界ではなく、**設定の欠陥**である（F10）。
`RateLimiterConfig` の既定が「容量60・毎秒1トークン補充」であり、かつ
`RateLimiter.configure(scope, ...)` が本番配線から一度も呼ばれないため、登録済み Provider の
`rateLimits`（rpm/tpm/concurrent）がレート制限に**まったく反映されない**。結果として
すべてのスコープが毎秒1リクエストに絞られる。

### 3.5 測定の限界（必読）

1. **クライアントとサーバが同一JVM・同一マシン**で動作しており、8コアを奪い合う。
   227 req/s は APAP の上限ではなく、この構成での値。分離環境での再測定が必要。
2. Provider は遅延ゼロのモックで、実 Provider の I/O 待ちによる並行性の効果（待ち時間中に
   他リクエストを処理できる）を再現していない。実構成ではスループットが上がる可能性が高い。
3. max 値（376ms / 761ms）は GC とJITの影響を含む。分位点と併せて見ること。
4. メモリ8GBの開発機であり、本番想定のノードスペックではない。
5. NFR-PRF-004（Cache Hit時 p99 ≤ 20ms）と NFR-PRF-005（同時Streaming 10,000接続）は
   **未計測**。前者は計測経路の追加のみで測れる。後者は単一マシンでは検証できない。

---

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
| **D4** | NFR-PRF-001 の計測区間に対応する計測点が 2.19 にも実装にも無い（`phase=gateway` ラベルだけが定義されている） | NFR-PRF-001, NFR-PRF-002, NFR-OBS-002 | [ADR-0034](adr/ADR-0034-gateway-phase-overhead-measurement-point.md) |

### 5.3 実装の不備（修正タスク）

「本番配線から生成されないクラス」を機械的に洗い出し、KDocだけの言及と実際の構築を区別して確認した。

| # | 内容 | 根拠 | 影響する要件 | 状態 |
|---|---|---|---|---|
| **F1** | **`AuditEngine` が本番配線に存在しない**。`ExecutionEngineComposer` にも `ApapEngineBuilder` にも構築箇所が無く、`ApapRepositories` に `AuditRepository` も含まれない。単体テストと `CapabilitySmokeTest` は**テストハーネスが明示的に配線**して緑になっている | `apap-runtime`/`gateway` の main から `AuditEngine` への参照ゼロ（KDoc言及のみ） | FR-OBS-001, FR-SEC-006, FR-SEC-007, FR-RTE-006, NFR-DAT-002の実効性 | 未修正 |
| **F2** | **Provider健全性の周期監視が起動されない**。`HealthCheckService` は `ApapEngineBuilder` から構築されるが、`ProviderHealthAggregator` はどこからも構築されず、Scheduler も無いため `ProviderHealthChanged` が一度も発火しない | 同上 | FR-PRV-006, FR-OBS-006 | 未修正（D2に依存） |
| **F3** | **`CapabilityRegistry` が本番配線に存在しない**。参照側（`CapabilityDiscoveryQuery`）は `GET /v1/capabilities` として到達可能だが、スキーマ登録側が機能しない | 同上 | FR-CAP-017, NFR-EXT-003 | 未修正 |
| **F4** | **Gateway が `messages[].role` を黙って捨てる**。`messages.flatMap { it.content }` で平坦化され role が失われる。同じ関数は未対応の `ContentPart.type` を `INVALID_REQUEST` で明示的に弾いており、扱いが不整合 | `apap.gateway.dto.ExecutionDto` の `toApapRequest` | FR-CAP-001 | 未修正（D1に依存） |
| **F5** | `SessionManager` が本番配線に存在しない（`/v1/sessions` は NOT_IMPLEMENTED として明示済み） | 同上 | FR-CTX-001 | 未修正 |
| **F6** | Gateway の本番起動（`Main.kt`）が In-Memory 既定のまま。JDBC/Redis 実装を使う構成が配線されておらず、複数Podでの水平スケールが成立しない | `Main.kt` に `Jdbc`/`Redis` の参照なし | NFR-AVL-003, NFR-EXT-005 | 未修正 |
| **F7** | **Tool Calling 経路のテストが1件も無い**。往路・復路とも配線されているが、動作は未検証。加えて 5.4 後半の `tool_results` を表現する型がリポジトリのどこにも存在しない | `tool_results`/`toolResults` の全文検索で0件 | FR-CAP-005 | 未修正 |
| **F8** | Admin の公開口が複数欠落（Credential rotation、Canary比率設定、analytics 3本、plugins scan/一覧、quotas/budgets、cache invalidate）。26エンドポイントが NOT_IMPLEMENTED | `EndpointCatalog`（理由付きで明示済み） | FR-PRV-005, FR-MDL-004, FR-OBS-004, NFR-EXT-001 ほか | 未修正（理由は明示済み） |
| **F9** | `AuditRepository.search` がテナント横断の読み出しを許す | `AuditSearchCriteria.tenantId` が nullable 既定 null | FR-SEC-003 | 未修正（D3に依存） |
| **F10** | **`RateLimiter.configure()` が本番配線から一度も呼ばれない**。登録済み Provider の `rateLimits`（rpm/tpm/concurrent）はレート制限に反映されず、全スコープが既定（容量60・毎秒1補充）で動作する。結果として**出荷時のスループット上限は毎秒1リクエスト** | `.configure(` の本番コード全文検索で該当0件。実測 4.7 req/s（§3.3） | FR-EXE-003, NFR-PRF-003 | **一部修正**（下記） |
| **F11** | **`MetricsEngine` が二重に購読され、イベント起因のメトリクスが全て2倍**になっていた。`ExecutionEngineComposer.build()` と `ApapEngineBuilder.build()` の両方で構築され、`IdempotentEventHandler` の重複排除は**インスタンスごと**のため両方が処理していた | `MetricsSubscriptionTest` が修正前に実測値2を観測 | FR-OBS-002, NFR-OBS-002 | **修正済み** |

#### P11で修正したもの

- **F11（二重計上）**: `ApapEngineBuilder` 側の重複構築を削除。`MetricsSubscriptionTest` を追加し、
  `CacheHit` を1件publishしてカウンタが**ちょうど1**であることを検証する。
  既存の `CapabilitySmokeTest` は `ExecutionEngineComposer` を直接使うハーネスのため
  この不具合を検出できなかった（本番の入口を通らないテストは本番の配線を検証できない）。
- **F10（一部）**: `ApapEngineBuilder.rateLimits(capacity, refillPerSecond)` を追加し、
  埋込ホストが上限を設定できるようにした。**Provider の `rateLimits` を反映する配線
  （`RateLimiter.configure` の呼び出し）は未実装のまま**であり、F10 は解消していない。
  なお、この設定口を追加する過程で「`RateLimiterConfig` 型は `apap-cache` にあり
  `implementation` スコープのため埋込ホストから見えない」ことが分かった。引数を
  プリミティブにして解決している（`HostCompileClasspathTest` が保証する分離の帰結）。

### 5.4 不変条件9: 違反注入による検査の確認

新規追加・変更したすべての検査について、意図的な違反を注入して落ちることを確認し、その後撤去した。

| 検査 | 注入した違反 | 結果 |
|---|---|---|
| `ModuleScanCoverage`（3検査から呼ばれる） | `settings.gradle.kts` に `probe:scope-probe` を仮追加 | **3検査すべて失敗**。`走査対象から漏れているモジュール: probe/scope-probe` と対処方法を明示 |
| `RepoWideScanRegistrationTest` | `scannedRoots` を宣言し登録しないテストを追加 | **失敗**。該当ファイルを名指し |
| `CredentialLeakageTest` | `GatewayServer` にBearerトークンをWARNログへ出力する行を挿入 | **失敗**。`Credentialの実値が出力へ混入しました: [CANARY-BEARER-...]` |
| `MetricsSubscriptionTest` | `ApapEngineBuilder` に `MetricsEngine` の重複構築を復元 | **失敗**。`apap_cache_events_total が 2 です`（期待1） |
| `TenantScopedRepositoryTest`（網羅性検査） | — | 追加時点で `AuditRepository` の欠落を**実際に検出**（注入不要で実欠陥を発見） |

`ModuleScanCoverageTest`（8ケース）は除外・理由必須・取り残し・プレフィックス衝突の各分岐を
人工データで直接実行しており、逃げ道が未実行のまま残らないようにしている。

---

## 6. prompt-engine への統合を開始してよいか

### 判断: **限定的に開始してよい。ただし下記6.1の3件は開始前に解消すること。**

### 根拠（開始してよいと判断する理由）

1. **ホスト互換性が機械検証されている**。`integration/host-compat` が、prompt-engine が実際に持つ
   依存（`apap-runtime` + `apap-api` のみ）でコンパイルできることを検証し、
   `HostCompileClasspathTest` が内部モジュールの非混入を検証している。統合ドキュメントの
   コード例は全て実コードから生成され、`DocumentedSnippetTest` が乖離を検出する（ADR-0029）。
2. **中核フロー（Chat / Streaming / Embedding）が本番の組立入口を通ってE2E検証されている**。
   `ApapEngineBuilderTest` の3本は `ApapEngineBuilder`（prompt-engine が使うのと同じ入口）を
   通っており、単体テストだけの根拠ではない。
3. **失敗経路が正規化されている**。内部例外は `apap.api.ApapException` へ正規化され、
   ホストから型として捕捉できる。13.4 のエラーコード体系はクローズドセットテストで固定。
4. **性能は要件を満たす（NFR-PRF-001）**。付加レイテンシ p50 0.836ms / p99 11.497ms は
   目標（15ms / 50ms）に対して十分な余裕がある。

### 6.1 開始前に必ず解消すべき項目

| # | 項目 | 理由 |
|---|---|---|
| **1** | **F10: レート制限の既定値** | 出荷時の既定で**毎秒1リクエスト**に絞られる。prompt-engine が組み込んだ瞬間にスループットが1 req/sになり、原因が「APAPが遅い」と誤診される。最低限、`ApapEngineBuilder.rateLimits(...)` での上書きを `docs/integration/prompt-engine.md` に明記すること（設定口はP11で追加済み）。本来の解決は Provider の `rateLimits` を `configure()` へ反映する配線 |
| **2** | **F1: AuditEngine未配線** | 監査ログが1件も記録されない。「動いているはず」と誤解したまま本番運用に入ると、FR-SEC-006 の監査要件を満たさないことに事故が起きるまで気づけない。配線するか、**記録されないことを統合ドキュメントに明記**するかのどちらかが必須 |
| **3** | **D2/F2: 周期実行が動かないことの明示** | Health Check・Rotation・保持期間削除のいずれも動かない。ADR-0032 の方針（ホストが `scheduledTasks` を駆動する）を実装するか、少なくとも**何が動かないか**を統合ドキュメントに列挙すること |

3件に共通するのは「**実装があるので動くと思い込む**」リスクであり、本プロジェクトが
繰り返してきた失敗そのものである。コードの修正が間に合わない場合でも、
**統合ドキュメントへの明記は必須**とする。

### 6.2 統合後に着手すべき項目（開始をブロックしない）

- F11 は修正済み。ただし修正前のメトリクス値は2倍だったため、**過去の計測値は信用しないこと**
- F4/D1（role欠落）: System Prompt を使う機能を prompt-engine 側で計画しているなら、
  それより前に ADR-0031 の実装が必要
- F7（Tool Calling のテスト皆無）: Tool Calling を使う予定があるなら、統合前に検証が必要
- F6（Gateway が In-Memory 既定）: prompt-engine は埋込利用のため直接は影響しない。
  ただし埋込側も既定は In-Memory であり、**プロセス再起動で Provider/Model 登録が失われる**。
  永続化が必要なら `apap-infrastructure-jdbc` への差し替えが要る（統合ドキュメントに記載済み）
- NFR-PRF-003（スループット）: 227 req/s は同一マシン計測であり、分離環境での再測定が必要

---

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
