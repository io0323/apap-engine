# P11 総合検証レポート（P12 是正作業を反映）

実装全体を `docs/design/` に対して検証した結果と、その指摘に対する是正の記録。

- P11（検証）: 2026-09-03。**コードの追加より不足の発見を優先**し、
  問題を「設計の不備」（ADR起票）と「実装の不備」（修正タスク）に分けた
- P12（是正）: 2026-09-03。統合前の必須項目を解消し、性能を測り直した。
  **P11の性能数値は破棄し、3章を全面的に差し替えた**
- P13（是正の続き）: 2026-09-03。F4の影響範囲を確定して修正し、F3を解消。
  残る未解決項目に対応時期を割り当てた（8章）
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

| 状態 | P11時点 | P12是正後 | P13是正後 |
|---|---|---|---|
| 実装済 | 37 | 43 | **47** |
| 部分実装 | 46 | 41 | 38 |
| 未実装 | 9 | 8 | 7 |
| 対象外 | 3 | 3 | 3 |

P12で「実装済」へ移ったのは FR-PRV-006（健全性監視）/ FR-OBS-001・FR-SEC-006・FR-RTE-006（監査）/
FR-CAP-005（Tool Calling）/ NFR-PRF-001（付加レイテンシ）。
P13ではさらに FR-CAP-001（role脱落の修正）/ FR-CTX-002（履歴の発話者帰属）/
FR-CAP-003（Structured Output）/ FR-CAP-017・NFR-EXT-003（CapabilityRegistry配線）/
FR-PMT-002（入力検証の正規化）。いずれも**本番配線からの到達とその経路を通るテスト**が
揃ったことによる。

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

### 3.6.1 再計測に必要な条件（P13で明記）

現環境では **NFR-PRF-003 は判定不能**である。判定するには次を満たす計測が要る。

**必須条件**

1. **負荷生成側とサーバを別マシンに分離する。** 現在はクライアントとサーバが
   同一の8コアJVMを共有しており、エンジンを通さない素のKtorルートでも
   400 req/s 程度で頭打ちになる。この構成では「1,000 req/s に届かない」が
   実装由来か負荷生成由来かを原理的に区別できない。
2. **負荷生成側の飽和判定を計測に含める。** 分離後も、負荷側のCPU使用率と
   「エンジンを通さない対照ルート」のスループットを同時に記録し、
   対照が目標値を十分上回っていることを確認してからエンジン側の数値を読む。
3. **サーバ側のスペックを記録する。** コア数・メモリ・JVMヒープ設定
   （現在は`-Xmx`未指定の既定値）。

**測定すべき指標**

| 指標 | 目的 |
|---|---|
| スループット（req/s） | NFR-PRF-003 の判定 |
| レイテンシ分布（p50 / p90 / p99 / max） | 平均値では飽和の兆候が見えない。飽和点では p99 が先に跳ねる |
| **ロック競合率**（BLOCKEDスレッド比率、および `DefaultQuotaManager` / `CircuitBreaker` フレームの出現率） | ADR-0036の判断材料。並列度を変えて競合率が超線形に増えるかを見る |
| `apap_overhead_duration_seconds` の phase 別分布 | どの段が伸びるかの内訳（ADR-0034で計測点を整備済み） |
| GC統計（回数・停止時間） | max値の跳ねがGC由来かの切り分け |

**ロック競合について**: 現在4.5%（BLOCKED 1,046 / 全23,140サンプル）で律速とは言えないが、
競合は負荷に対して超線形に増えるため高負荷では律速になり得る。
CAS化の可否を検討し、**単独では採らない**という結論を [ADR-0036](adr/ADR-0036-lock-free-rate-limiter-and-circuit-breaker.md) に記録した
（競合がスコープ単位の単一ホットキーへ集中するため、CASはスピンして悪化しうる。
効くのはストライピングとクリティカルセクション削減で、CAS化はその後）。

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

**P13での修正（待ち時間の上限）**: フルビルドの並列実行下でこのテストが
`UncompletedCoroutinesError`（`runTest`の既定60秒）で落ちた。単独実行では2回連続で緑、
`--rerun-tasks`でのフル再実行でも緑であり、**漏洩の検出そのものではなく実時間の上限**が
原因だった。本テストは8往復のHTTP（うち2本はSSEで、本文の読み切りにheartbeat周期分待つ）を
行い、フルビルド中は他モジュールのテストと同じマシンを奪い合う。待ち時間の上限は検査対象では
ないため、`runTest(timeout = 5.minutes)` + `runTestApplication` へ変更して負荷に左右されない
余裕を取った（既定60秒は他のテストではそのまま）。変更後、意図的にsentinelをログへ注入して
**タイムアウトではなく漏洩アサーション**（`CredentialLeakageTest.kt:112`）で落ちることを
確認している（不変条件9）。

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
| **F3** | **`CapabilityRegistry`が本番配線に存在しない**。参照側は到達可能だが、スキーマ登録側が機能せず、P7で自前バリデータからjson-schema-validatorへ差し替えた作業も実行経路に乗っていなかった | FR-CAP-017, NFR-EXT-003, FR-CAP-003 | **解消**（P13）。`ApapEngineBuilder`で構築し`ApapAdmin.capabilities`として公開。併せて`JsonSchemaValidator`を切り出し、リクエスト単位の`outputSchema`検証（FR-CAP-003）にも使う。`SchemaValidationE2ETest`が検証 |
| **F4** | **roleが3箇所で失われる**（下記5.5参照）。System Promptは供給経路そのものが無く、マルチターン履歴は発話者不明の平坦な連結としてProviderへ渡っていた | FR-CAP-001, FR-CTX-002 | **解消**（P13、ADR-0031）。`InputMessage`をドメイン・SPI・公開API・Gateway DTOへ通し、Context組立てとrefitでrole境界を保つ。`MessageRoleE2ETest`が到達内容を検証 |
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

### 5.5 F4の影響範囲の確定（P13 作業1）

「`messages[].role` は受け取っても効かない」の実際の影響を、コード経路を追って確定した。
結論は**区別が失われる**——最も重い読みが正しかった。

| 問い | 実際 |
|---|---|
| system / user / assistant の区別が失われているか | **失われていた。** 3箇所で独立に脱落する（下表） |
| System Prompt は現在どう扱われていたか | **供給経路が存在しなかった。** `ExecutionEngine.buildContextualPrompt` が `contextManager.build(request, systemPrompt = emptyList(), ...)` とハードコードしており、Context Manager側にsystemPromptの受け口はあるのに常に空が渡っていた |
| マルチターン履歴で発話者の帰属が保たれているか | **保たれていなかった。** `assembled.turns.flatMap { it.contentParts }` で `Turn.role`（SYSTEM/USER/ASSISTANT/TOOL）を捨てて平坦化していた。会話履歴は保存側では役割を持つが、Providerへ渡る時点で失われる |
| Adapter へ渡る時点で role 情報がどうなっていたか | **型として存在しなかった。** `AdapterRequest.input: List<ContentPart>` で、Adapterは受け取りようがない |

**脱落箇所（3つとも独立）**

1. Gateway: `ChatRequestDto.toApapRequest` が `messages.flatMap { it.content }` で平坦化
2. Engine: `buildContextualPrompt` の `systemPrompt = emptyList()` と履歴の `flatMap`
3. SPI: `AdapterRequest.input` が `ContentPart[]`

Gatewayだけ直しても2と3で落ちるため、**縦に全部通す必要があった**。

**影響の性質**: 429のように騒がしく失敗せず、Providerには
「区切りのない1人の発話」として届く。全チャットリクエストが静かに劣化する種類の欠陥であり、
指示のとおり統合前の必須項目として修正した。

**修正**（ADR-0031の決定1・2・4に沿う）:

- `InputMessage(role: TurnRole, content: List<ContentPart>)` を追加し、
  ドメイン → SPI → 公開API → Gateway DTO を縦に通した。`TurnRole` は
  04_ドメイン設計.md 4.3.4 の既存enumを再利用している（role概念を二重に作らない）
- `buildContextualPrompt` はリクエストのSYSTEM発話をContext Managerへ渡し、
  履歴の各Turnを role 付きのまま合成する
- `ContextManager.refit` は**発話単位**で切り詰め、1発話まで減らしてもなお入らない場合のみ
  その発話**内**のContentPartを落とす（role境界を跨がない）
- `PromptDraft` / `PromptOptimizer` も messages を一貫して更新する
  （片方だけ最適化するとProviderへ渡る内容とトークン計上がずれる）
- Gatewayは未知のroleを**黙って無視せず** `INVALID_REQUEST` で拒否する
  （同ファイルが `ContentPart.type` に対して既に採っている方針と揃えた）
- role未指定の入力（`input` のみ）は単一のUSER発話として扱う。
  SYSTEMやASSISTANTへ昇格させるのは危険側の推測になるため

**残る限定事項**: ADR-0031の決定3（PromptTemplate参照フィールド）は未実装。
`RenderingStage` はパススルーのままで、FR-PMT-004は部分実装として残る。

## 6. prompt-engine への統合を開始してよいか（P13 是正後の判断）

### 判断: **開始してよい。** 統合前の必須項目はすべて解消した。

### 6.1 必須としてきた項目の最終状態

| # | 項目 | 状態 | 根拠 |
|---|---|---|---|
| 1 | F10: レート制限の既定値（毎秒1リクエスト） | **解消**（P12） | `ProviderRateLimitConfigurer`。`ProductionWiringTest`が絞りの実効性を実測 |
| 2 | F1: AuditEngine未配線 | **解消**（P12） | `ProductionWiringTest`がAuditRecordの実在を検証 |
| 3 | D2/F2: 周期実行 | **解消**（P12） | `ScheduledTask` Port（ADR-0032）。`SequenceFlowE2ETest` |
| 4 | Tool Calling / Function Calling | **解消**（P12） | `ToolResult`で往復成立。`ToolCallingE2ETest` |
| 5 | **F4: role脱落** | **解消**（P13） | 3箇所の脱落を縦に修正。`MessageRoleE2ETest`がAdapter到達内容を検証（5.5） |
| 6 | **F3: CapabilityRegistry未配線** | **解消**（P13） | 本番配線＋`outputSchema`検証。`SchemaValidationE2ETest` |

### 6.2 統合してよいと判断する根拠

1. **ホスト互換性が機械検証されている**（`integration/host-compat`、ADR-0029）
2. **10本のシーケンスすべてにE2Eテストがある**（2章）
3. **本番の入口を通した配線検証がある**（`ProductionWiringTest`）。
   P11で見つかった不具合はいずれも「単体テストは緑・本番では動かない」型だった
4. **AACPが最初に使う機能が検証済み**: Chat（role区別込み）・Tool Calling（往復込み）・
   Structured Output（是正リトライ込み）がいずれもビルダ経由のE2Eで通っている
5. **付加レイテンシは要件を大きく下回る**（p50 0.335ms / p99 2.295ms、目標15ms/50ms）

### 6.3 統合時に必ず伝えるべきこと（ドキュメント必須事項）

コードでは解決しておらず、**知らないと誤解する**もの。`docs/integration/prompt-engine.md` に記載する。

| 項目 | 内容 |
|---|---|
| 周期タスクの駆動 | `engine.scheduledTasks`を宿主が回さない限り、Providerの健全性監視は動かない |
| レート制限 | テナント別制限は既定で掛からない（ADR-0035）。`tpm`/`concurrent`は未反映（F12） |
| 監査ログの保存先 | 既定はIn-Memoryでプロセス再起動で消える。`JdbcAuditRepository`へ差し替えること |
| 永続化 | Provider/Model登録も既定はIn-Memory |
| スループット | 605 req/s は同一JVM上のHTTP経由での観測値であり**上限ではない**（3.6） |
| System Prompt | `messages[].role` で渡すこと。`input` だけを使うと単一のUSER発話として扱われる |
| PromptTemplate | `RenderingStage`はパススルー。テンプレート描画は実行経路に入らない（F13） |

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

---

## 8. 未解決項目の対応時期（P13 作業3）

「統合前 / 統合と並行 / Gateway運用開始前」で分類した。作業量は概算（実装＋テスト＋レビュー）。

### 8.1 優先度: 情報漏洩に関わるもの

| # | 項目 | 対応時期 | 判定理由 | 概算 |
|---|---|---|---|---|
| **F9** | `AuditRepository.search` がテナント横断の読み出しを許す（`AuditSearchCriteria.tenantId` がnullable既定null） | **Admin API実装前に必須**（他項目より先） | **他と同列に扱わない。** これは性能や利便性ではなく**テナント間の情報漏洩**の問題である。ただし現時点で到達経路は無い——`GET /admin/v1/audit` はNOT_IMPLEMENTEDで、`search` の本番呼び出し元も存在しない。したがって**今すぐ漏洩する状態ではない**が、Admin APIを実装した瞬間に境界の無いAPIができあがる。「実装してから直す」順序にしてはならない | 0.5〜1日（Port契約の変更とテスト。ADR-0033に方針あり） |

**F9の扱いについて**: 統合そのものはブロックしない（埋込利用では`search`を呼ばない）。
しかし `GET /admin/v1/audit` の実装チケットには**必ずF9の修正を先行タスクとして紐づける**こと。
併せて、エンドポイント実装時にテナントIDを認証済みトークンのクレームから取り、
クエリパラメータで上書きさせないこと（ADR-0033）。

### 8.2 統合と並行でよいもの

| # | 項目 | 判定理由 | 概算 |
|---|---|---|---|
| **F5** | SessionManager未配線（FR-CTX-001） | `/v1/sessions` はNOT_IMPLEMENTEDとして明示済みで、黙って壊れているわけではない。埋込利用ではセッション管理は宿主側（prompt-engine）が持つのが自然で、APAP側のSessionが要るかは統合設計で決まる。**要否が決まる前に実装すると、使われないコードが増える** | 1〜2日（要否確定後） |
| **F12** | `tpm`/`concurrent` がレート制限へ未反映（FR-EXE-003） | `rpm`は反映済みで、レート制限そのものは機能する。`tpm`はトークン数を`cost`として消費すれば同じバケット機構に載る見込み。`concurrent`はセマフォ的な別機構が要る。実Providerを繋いで`tpm`超過を実際に踏むまで、正しい設計が決まらない | tpm: 1〜2日 / concurrent: 3〜5日（別機構） |
| **F13** | PromptTemplate未配線（FR-PMT-004、ADR-0031の決定3） | `CanonicalRequest`にテンプレート参照フィールドが無く、`RenderingStage`はパススルー。テンプレートをリクエスト単位で指定するのかPolicy側で解決するのかが未決（ADR-0031の「未決定のまま残る事項」）。**設計判断が先** | 2〜3日（設計確定後） |
| **NFR-PRF-002 p99** | 初回チャンク付加遅延 p99 49.853ms（目標30ms） | **原因は未特定。** p50 5.2ms / p90 12.5ms に対しp99だけが跳ねており、GCかスレッドスケジューリングの可能性が高いが**確認していない**。同一JVMにクライアントが同居する計測構成の影響も切り分けられていない。要件文に分位点の指定が無く、p50/p90基準なら満たす。分離環境での再計測（3.6.1）と同時に切り分けるのが効率的 | 切り分け0.5日＋対処は原因次第 |

### 8.3 Gateway運用開始前でよいもの

| # | 項目 | 判定理由 | 概算 |
|---|---|---|---|
| **F6** | GatewayがIn-Memory既定（NFR-AVL-003 / NFR-EXT-005） | **埋込利用（prompt-engine）には影響しない**——ホストが`ApapRepositories`を差し替えるため。Gatewayを常駐プロセスとして本番運用する段階で必須になる。複数Podでの水平スケールもここに依存する | 1〜2日（設定駆動化とJDBC/Redis配線、起動時の疎通確認） |
| **F8** | Admin公開口の欠落（rotation / Canary比率 / analytics / plugins scan / quotas / cache invalidate） | 26エンドポイントが`EndpointCatalog`で理由付きNOT_IMPLEMENTED。**黙って501ではない**ため、利用側は何が無いか分かる。必要になった機能から個別に足せる。ただしCredential Rotation（FR-SEC-002）は運用上いずれ必須 | 各0.5〜1日 × 必要な数（rotationを最優先） |

### 8.4 対応時期を割り当てていないもの

| # | 項目 | 状況 |
|---|---|---|
| NFR-PRF-003 | **判定不能**。分離環境での再計測が前提（3.6.1）。ロック競合のCAS化はADR-0036で「単独では採らない」と決定 |
| NFR-PRF-004 / 005 | 未計測。004（Cache Hit p99）は計測経路の追加のみ。005（同時Streaming 10,000接続）は単一マシンでは検証できない |

---

## 9. リクエスト忠実性（Request Fidelity）の網羅検査（P14）

F4（role脱落）とF3（outputSchema未検証）は同じ構造の欠落だった。既存のテストは
「応答が返る」「イベントが飛ぶ」という**結果**を見ており、**Adapterが何を受け取ったか**を
見ていなかった。APAPの中核機能は共通リクエストをProviderリクエストへ変換することなので、
そこが最も検証されるべき地点である。2件出た以上、他のフィールドにも同じ穴があると考え、
公開リクエスト型の全フィールドを網羅的に検査した。

### 9.1 分類表（クローズドセット）

`modules/apap-runtime/src/test/kotlin/apap/runtime/fidelity/RequestFidelityContract.kt` に、
`ApapRequest`(16フィールド) / `CanonicalRequest`(18フィールド) の**全フィールド**を
次の3分類で宣言した。分類は単一の場所にあり、`RequestFidelityContractTest` が
`MetricsCoverageTest` / `DomainEventCoverageTest` と同じクローズドセット方式で機械検証する。

| 分類 | 対象フィールド |
|---|---|
| Adapterへ到達すべき（変換後の形も宣言） | `capabilityId` / `input` / `params` / `tools` / `toolResults` / `outputSchema` / `timeoutBudget`→`timeout`（残予算） / `traceId`→`traceHeaders["traceparent"]` / `messages` |
| Adapterへ到達してはならない | `tenantId` / `principal` / `sessionId` |
| APAP内部で消費される | `modelAlias`（Routing→物理名） / `conversationId` / `idempotencyKey` / `requestId` / `constraints` / `preferences` |

`RequestFidelityContractTest` が落とすのは次の場合である。

1. リクエスト型にフィールドを足したのに分類を書かなかった（＝F4/F3が生まれた形）
2. 分類表に実在しないフィールドが残っている
3. 到達先として実在しない`AdapterRequest`フィールドを書いた
4. **どのリクエストフィールドからも埋まらない`AdapterRequest`フィールドがある**
   （`modelName`/`authContext`のようにAdapter側で作るものは理由付きで明示登録する）
5. 到達しないと宣言したフィールドに見張り値（probe）が無い
   （文字列として追えない場合は理由の明示を必須にし、黙って対象外にできないようにした）

### 9.2 到達性の実測

`RequestFidelityE2ETest`（21件）が、全フィールドを区別可能な見張り値で埋めたリクエストを
**本番の入口（`ApapEngineBuilder`）で組んだエンジン**へ通し、`adapter-mock`をデコレートした
`RecordingAdapter`が受け取った`AdapterRequest`を直接読む。検査は非Streaming／Streamingの
両方で行う——F4では`DefaultPromptEngine`と`ContextManager.refit`が**別々に**roleを
落としており、片方の経路だけでは両方は見つからなかった。

| 検査 | 非Streaming | Streaming |
|---|---|---|
| 「到達すべき」全フィールドが正しい値で届く | ✔ | ✔ |
| 「到達してはならない」値が1つも含まれない（`AdapterRequest`全文を走査） | ✔ | ✔ |
| `messages`のroleと順序 | ✔ | ✔（System Prompt） |
| ContentPartの各modality（text / image / audio） | ✔ | — |
| Conversation履歴がroleを保って届く | ✔ | — |
| Memory注入がSYSTEMとして届く | ✔ | — |
| Fallback後（次候補）も同じ内容が届く | ✔ | — |
| Credentialの実値が含まれない | ✔ | — |

### 9.3 検出した脱落（3件、いずれも修正済み）

| # | 脱落 | 影響していた要件 | 症状 |
|---|---|---|---|
| **1** | Structured Outputの**是正指示が`messages`に入っていなかった**（`RequestMapper.withCorrectionNote`が`input`にだけ追記） | FR-CAP-003 / ADR-0011 決定5 | Adapterは`messages`を読んでProvider形式へ変換するため、是正指示が届かない。**是正リトライが同一プロンプトの単純再送**になっていた（ADR-0011が「無意味なため避ける」と明記した挙動そのもの） |
| **2** | 会話履歴のuser turnに`request.input`（System Promptや利用側指定のassistant発話を含む平坦列）を丸ごと書いていた | FR-CTX-002 / ADR-0031 | 次のターンで履歴として読み戻すと「ユーザがシステムプロンプトを喋った」ことになる。F4で入口のroleを直しても、**履歴経由で発話者が壊れ続ける** |
| **3** | 実`QueryEmbedder`を供給する口が本番の入口に無かった | FR-CTX-004 | `ExecutionEngineComposer`はファクトリ引数を持つが`ApapEngineBuilder`が露出しておらず、ホストからMemory注入を有効化できない。実装済みだが到達不能——F1/F3と同じ形 |

いずれも「応答は正常に返る」ため、結果だけを見るテストでは検出できない種類の欠落である。

**脱落が無かったフィールド**（検証済みであることを明示する）:
`capabilityId` / `input` / `params`の5項目すべて（temperature / maxTokens / topP / stop / seed）/
`tools` / `toolResults` / `outputSchema` / `timeoutBudget` / `traceId` / `messages`のrole・順序・modality。
到達してはならない側も `tenantId` / `principal` / `sessionId` / `modelAlias` / `conversationId` /
`idempotencyKey` / `requestId` / `constraints` の見張り値がAdapterへ届いていないことを実測で確認した
（`preferences`だけは`OptimizeFor`列挙のみで見張り値を置けないため、`RequestMapper`に対する
直接検証と分類表のクローズドセットで担保する）。

### 9.4 不変条件9: 違反注入による確認

| 注入 | 結果 |
|---|---|
| 分類表から`params`の項目を削る | `RequestFidelityContractTest` の3テストが失敗（「ApapRequest/CanonicalRequestに分類されていないフィールドがあります: [params]」「どのリクエストフィールドからも埋まらないAdapterRequestフィールドがあります: [params]」） |
| `RequestMapper`で`tools`の伝播を切る（`tools = null`） | `RequestFidelityE2ETest` の3テストが失敗（非Streaming / Streaming / Fallback後、いずれも「toolsが届いていません（FR-CAP-005）」） |
| `withCorrectionNote`を修正前（`input`のみ）へ戻す | 是正指示の到達テストが失敗し、届いたmessagesが1回目と同一であることを表示 |
| `queryEmbedding`のフックを無効化（P14前の状態） | Memory注入の到達テストが失敗 |

注入はいずれも一時的なもので、確認後に元へ戻している。

### 9.5 残る限界

- **ADR-0023のResilience経路がMemoryに適用できていない**: `QueryEmbedder.embed`は`parts`しか
  受け取らず、`ResilientQueryEmbedder`が要求するtenantId/traceId/providerId/modelIdを渡す口が
  無い。`ApapEngineBuilder.queryEmbedding`で渡した実装は**保護なしにそのまま呼ばれる**。
  KDocに明示してあり、黙って縮退させてはいない。解消には`embed`のシグネチャ拡張が要る
- **実ベクトル化の実体は引き続きAPAP側に無い**（FR-CTX-004の「未達」は変わらない）。
  今回の修正は「ホストが供給すれば実行経路に乗る」ところまでで、APAP自身は埋め込みを作らない
- `RenderingStage`はパススルーのまま（F13、8.2）
