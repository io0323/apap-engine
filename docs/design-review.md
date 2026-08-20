# 設計書レビュー（実装着手前の読解結果）

読解範囲: `docs/design/`（存在しない `README.md` の代わりにリポジトリルート `README.md`）→ 01 → 02 → 03 → 04 を通読、続けて 09, 10, 12, 13, 14 を通読。05〜08, 11 は見出しレベルのみ確認（未通読）。加えてリポジトリルートの `01_CLAUDE.md`（実装規約）および実際の `modules/` 構成を突き合わせ資料として参照した。

本ドキュメントは読解結果の記録であり、`docs/design/*.md` は一切編集していない。

---

## 1. 曖昧点一覧（実装上、値・挙動が一意に定まらない箇所）

| # | 該当章節 | 何が決まっていないか | 実装するには何を決める必要があるか | 推奨案 |
|---|---|---|---|---|
| A1 | 4.4 VO（ID系）/ CLAUDE.md実装規約 | ULID採番の具体アルゴリズム（使用ライブラリ、モノトニック生成の要否、同一ミリ秒内衝突時の挙動） | ULID生成ライブラリの選定、`IdGenerator` Portのシグネチャ（モノトニック保証の有無） | JVM実装が定まっているため、実績あるULIDライブラリ＋モノトニックファクトリを既定にし、`IdGenerator` Port経由で注入（CLAUDE.md 5項の方針に合致） |
| A2 | 2.9 / 4.6 TokenEstimationService | Providerが usage を返さない場合のトークン推定に使うトークナイザーの具体（モデルファミリー別辞書の有無、近似方式） | Provider横断で使う推定ロジック（1トークナイザーで代表するか、Provider Adapter側に推定を委譲するか） | 初期実装は簡易近似（文字数/4など）＋`estimated=true`フラグで許容し、精度が必要になった時点でAdapter側`fetchUsage`優先＋専用トークナイザーへ拡張。要確認事項#9参照 |
| A3 | 2.14 / 3.15 CacheStore SPI | Response/Request Cacheの既定実装「分散KVS」の具体製品 | Cache Store実装の技術選定 | 要確認事項#1参照（データストア選定と合わせて決定） |
| A4 | 2.11, 13.1 | `Idempotency-Key` 自動生成時のアルゴリズム（クライアント未指定時） | 自動生成キーの生成規則（内容ハッシュか乱数か）、Request Cacheの24hウィンドウとの関係 | `tenant_id + capability + canonical_request正規化JSON`のハッシュ（Response Cacheキーと同様の方式）を既定案とし、リクエストの意味的重複を自動的にも抑止する |
| A5 | 4.6 CanaryResolutionService, 10.3 | `hash(requestId)%100` のハッシュ関数の具体（アルゴリズム） | 決定的かつ均等分散するハッシュ関数の選定 | 標準的な非暗号ハッシュ（例: xxHash/FNV-1a等の言語標準ライブラリ相当）で十分。暗号強度は不要 |
| A6 | 4.4 Region VO | 「ISO 3166ベースの内部コード表」の実値一覧が存在しない | サポートするリージョンコードの列挙（例: `jp`, `us-east` 等）とModel.regions/Provider.regionsとの対応 | 要確認事項#6参照（提供地域はビジネス判断） |
| A7 | 1.7.5 FR-PMT-002 | Prompt Validationの「禁止パターン」「インジェクション検査」の具体的な検査ロジック | 禁止パターンのルールセット、インジェクション検知の実装方式（ルールベースか分類器か） | 初期はルールベース（サイズ上限・既知の攻撃パターン正規表現）から開始し、SPI差替可能にして高度化は将来対応 |
| A8 | 1.7.10 FR-SEC-007, 3.8 MaskingStrategy | `RegexPiiMaskStrategy` の対象PII種別・具体正規表現が未定義 | マスキング対象PIIカテゴリ、正規表現ルール、誤検知時の扱い | 要確認事項#5参照（法務・コンプライアンス判断が必要） |
| A9 | 2.16 Context組立 / 2.12 Fallback仕様 | 圧縮後もcontext window超過（`CONTEXT_LENGTH_EXCEEDED`, 422）になった場合、圧縮戦略を変えて自動リトライするのか、即エラーとするのか | 「圧縮→検証→再圧縮」のループ有無・上限回数 | 自動リトライはせず即422とする（設計書10.1のアクティビティ図でも圧縮は一度きりの扱い）。必要になれば将来ADRで拡張 |
| A10 | 2.11 MODEL_ERROR行 vs 3.3.5 AttemptExecutor疑似コード | Structured Output是正リトライ（最大2回）が、`AttemptExecutor`の通常試行ループ（`maxAttempts`既定3）と同じ試行回数バジェットを共有するのか、別枠なのか | 是正リトライと通常Retryの回数管理を統合するか分離するかの実装方針 | 要確認事項#10参照（コスト影響があるため確認） |
| A11 | 3.3.3 DefaultExecutionEngine.execute() / 2.8 Request Flow | Cache hit時（ステップ4で短絡）にQuota消費・Rate Limit消費が発生するか | キャッシュヒットとQuota/Rate Limitの関係 | Cache hit時はProvider呼出コスト0（2.14に明記）なのでQuota上のトークン消費もカウントしない、が使用回数としてのRate Limit（テナント流量）は消費する、を既定案とする |
| A12 | 2.11 タイムアウト予算(既定60s) vs 2.10 Streaming全体タイムアウト(既定300s) vs 3.3.1 `CanonicalRequest.timeoutBudget`（単一フィールド） | StreamingとNon-Streamingで異なる既定値を持つ全体タイムアウトを、単一の`timeoutBudget`フィールドでどう表現するか | Streaming時は`timeoutBudget`の既定値を実行モードに応じて切替えるのか、別フィールドを設けるのか | Executionモード（stream有無）に応じて`timeoutBudget`の既定値を切替える実装とし、フィールド自体は単一のまま踏襲 |
| A13 | 2.6.3 Provider Health判定 | 「連続3回probe失敗」のカウントがSchedulerのprobe専用か、パッシブヘルス（実トラフィック失敗）も合算されるか | UP/DEGRADED/DOWN判定ロジックにおけるprobe失敗カウンタとパッシブ失敗率の合成方法 | Probe失敗カウンタとパッシブ成功率は独立集計とし、いずれか一方の条件を満たせばDOWN判定とする（OR条件） |
| A14 | 2.5.2 step5 Load Balancing「差0.02以内」/ Sticky補正「+0.05」 | これらの定数がPolicyの `override.weights` 以外の項目として上書き可能かが3.14 PolicyRuleのoverride構造に明記されていない | PolicyRule.overrideのスキーマにload balancing閾値・sticky補正値を含めるか | 含める。`override`構造に`loadBalanceThreshold`, `stickyBonus`を追加（既定値は設計書の数値のまま） |
| A15 | 9.5 BatchJob状態遷移 note | 結果保持期間「既定7日」の起算点（`COMPLETED`到達時か、`submitted_at`からか） | 保持期間カウントの基準時刻 | 要確認事項#7参照（データ保持ポリシーとして確認） |
| A16 | 9.1 Provider状態遷移 note | DRAINING排出タイムアウト300s超過時に実行中リクエストを強制終了するのか、完遂を待ち続けるのか | タイムアウト超過時の強制終了ポリシー（クライアントへのエラー返却含む） | 300s超過後は`DISABLED`へ強制遷移し、残存実行中リクエストはキャンセル扱い（`RequestCancelled`発火）とする。ただし運用インパクトがあるため要確認事項候補ではあるが、既定挙動として提案し進める |
| A17 | 2.15 Session仕様 | 「有効期限既定24h（スライディング更新可）」のスライディング更新条件（毎リクエストで延長か、明示APIのみか） | スライディング更新のトリガー | 毎リクエストで自動延長を既定とし、Policyで無効化可能とする |
| A18 | 13.1 Admin API `/admin/v1/plugins:scan` | scan実行のトリガー条件（起動時自動か、Admin API呼出のみか、定期実行か） | Plugin Managerのscanタイミング仕様 | 起動時自動scan＋Admin API手動trigger＋`plugin.dir`のfs watchは行わない（ポーリングもしない）を既定とする |
| A19 | 3.15 DI構成 | 設定ファイルの完全なスキーマ（`application.yaml`は抜粋例のみ）。特にNFR-MNT-004の「宣言的（GitOps可能なYAML/API）」管理の対象範囲（Provider/Model/Policyすべてか、一部か） | 宣言的設定管理の対象範囲とYAML/Admin APIの使い分け | 初期実装はAdmin API（DB管理）を正とし、YAML/GitOpsは将来のインポート/エクスポート機構として後回しにする |
| A20 | 2.4 Capability一覧 `video_generation`, `fine_tuning` | 「将来」ステータスのCapabilityについて、Capability Registryへの型定義自体は今回スコープに含むか | スキーマ登録のみ先行させるか、完全に対象外とするか | 完全対象外とし、Registryにも登録しない（GA分のみ実装） |

---

## 2. 章をまたいだ矛盾・不整合一覧

| # | 該当箇所 | 内容 |
|---|---|---|
| C1 | 4.3.1（CredentialRef VO: `state(ACTIVE/STANDBY/REVOKED)` の3状態）**vs** 9.7 状態遷移図（`STANDBY → ACTIVE → REVOKED_PENDING → REVOKED` の4状態） | 4.3.1の不変条件表に `REVOKED_PENDING` が欠落している。9.7の方が詳細（猶予期間つき失効を表現できる）なため、実装は9.7の4状態を正として進め、4.3.1相当のVO定義を拡張する必要がある。要確認事項#8参照 |
| C2 | 3.3.6（`QuotaManager.commit(reservation, actualUsage)` インターフェース定義）**vs** 3.3.3（`DefaultExecutionEngine.execute()` 疑似コード） | `execute()` 内で `quotaManager.checkAndReserve()` は呼ばれるが、`commit()` の呼出し箇所が疑似コードのどこにも存在しない。予約の確定/解放（成功時commit、失敗時release相当）のタイミングが設計書内で欠落している |
| C3 | 3.1（基本設計のパッケージ構成: `apap-gateway` は `apap/` 配下の1モジュールとして記載）**vs** 実リポジトリ構成（`gateway/apap-gateway` としてmodules/の外、トップレベル別ディレクトリ） | 実装済みの構成（CLAUDE.mdで追認済み）が設計書3章の記述と一致していない。設計書だけを見た実装者は`modules/apap-gateway`を探して混乱しうる |
| C4 | 実リポジトリ `modules/` に存在する `apap-runtime`, `apap-testkit` | 設計書3.1のパッケージ構成一覧にこの2モジュールの記載が一切ない。CLAUDE.mdで存在は追認されているが、design docs単体では未定義のモジュールが実装に必須になっている |
| C5 | 3.10（`class ApapFacade` という設計書上のクラス名）**vs** CLAUDE.md（`ApapEngine` / `ApapEngineBuilder` という型名） | 同一の「SDK単一入口」を指すと推測されるが、設計書とCLAUDE.mdで名称が異なり、同一物である旨がどこにも明記されていない |
| C6 | 2.11「タイムアウト予算（リクエスト全体timeout既定60s）」**vs** 2.10「ストリームアイドル既定60s／全体既定300s」 | 数値自体は矛盾ではないが、両者がCanonicalRequestの単一`timeoutBudget`フィールド（3.3.1）にどう収まるかが未整理（A12と関連する設計内部の整合性問題） |
| C7 | 13.4 エラーコード表 `QUOTA_EXCEEDED`（429, retryable=false）**vs** 429ステータスの一般的意味（Retry-Afterで再試行可能を示唆） | 429でretryable=falseは仕様上明記されているため矛盾ではないが、13.5「429/503には`Retry-After`付与」と組み合わせるとクライアント実装者が誤解しやすい（Retry-Afterがあるのに`retryable:false`）。ドキュメント上は正しいが、実装時のレスポンス生成で`Retry-After`ヘッダを付けない一貫実装が必要 |

---

## 3. 設計書に定義がないが実装に必須となる要素の一覧

| # | 要素 | 現状の記載 | 実装に必要な理由 |
|---|---|---|---|
| U1 | `Clock` Interfaceの設計書内定義 | docs/design内に一切記載なし（CLAUDE.mdのみが方針として言及） | Retry backoff、CB窓、TTL、Credential失効猶予等、時刻依存ロジック全てのテスト容易性に直結 |
| U2 | `IdGenerator` Interfaceの設計書内定義 | 同上 | ULID生成の決定的テストに必須 |
| U3 | 宣言的設定ファイルの完全なスキーマ | 3.15に抜粋例のみ | NFR-MNT-004の充足、Provider/Model/Policyの構成管理実装に必須 |
| U4 | テストダブル（In-Memory実装）の命名規則・契約テスト項目 | CLAUDE.mdが`apap-testkit`の存在とContract Testの方針のみ言及、具体項目はなし | Adapter開発者が満たすべきContract Testの合格基準の明確化に必要 |
| U5 | Tokenizer推定の技術詳細・使用ライブラリ | 2.9に「Tokenizer推定」とあるのみ | コスト計算・Quota事前チェックの精度に直結 |
| U6 | PIIマスキングの正規表現・対象カテゴリ | 3.8に`RegexPiiMaskStrategy`という名称のみ | FR-SEC-007充足、監査ログのコンプライアンス要件に必須 |
| U7 | Region内部コード表の実値一覧 | 4.4に「ISO 3166ベース」という方針のみ | Routingのregion制約フィルタ実装に必須 |
| U8 | gRPC/Protobufスキーマ本体 | 2.2.1で方針言及のみ、13章はREST限定 | 1.3.1で謳われているgRPC利用者（Backend API等）への対応に必須（要確認事項#3） |
| U9 | WebSocketプロトコル仕様 | 1.3.1で利用手段として言及のみ | Desktop Application向け対応に必須（要確認事項#3） |
| U10 | MQTT Bridge仕様 | 1.3.1で言及のみ | IoT向け対応に必須（要確認事項#3） |
| U11 | Idempotency-Key自動生成アルゴリズム | 2.11に「自動生成」とあるのみ | 冪等性保証の実装に必須 |
| U12 | Cache Store / 共有カウンタストア（Rate Limiter）/ CB共有ストアの具体技術 | 「分散KVS」等の抽象名のみ | インフラ構築・依存関係定義に必須（要確認事項#1） |
| U13 | Secret Store具体実装 | 3.15の設定例に`vault-compatible`という値があるのみ | Credential管理の実装・運用体制決定に必須（要確認事項#2） |
| U14 | データベースエンジンの選定 | 12章はリレーショナルER図（PK/FK/UQ）だが製品未指定。`MEMORY.embedding`はVECTOR型でベクトル検索要件あり | 永続化層（apap-infrastructure）実装の前提として必須（要確認事項#1） |
| U15 | マルチテナント解決の具体（CIAP JWTクレーム名） | 2.8「tenant解決」とあるのみ、CIAPとの結合仕様なし | Gateway層の認証・テナント解決実装に必須（要確認事項#4） |
| U16 | Quota予約のコミット/解放ライフサイクル | C2参照。インターフェースはあるが呼出しフローが未定義 | 予約リークやQuota誤集計を防ぐために実装前の整理が必須 |
| U17 | Health Check probeの「軽量疎通」の内容（2.6.3） | 「軽量疎通 + 認証検証」とあるのみで、Adapter毎にどんなリクエストを送るかは`healthCheck()`のAdapter実装依存としか読めない | Adapter開発者向けガイドライン（15章）との整合確認が必要（15章は今回未通読のため要再確認） |

---

## 4. 決着状況（ADR対応表）

要確認事項#1〜#10への回答（2026-08-20）を受け、`docs/adr/` にADRを起票した。一覧は `docs/adr/README.md` を参照。以下は本ドキュメントの各項目ID（A1〜A20 / C1〜C7 / U1〜U17）ごとの決着状況。「推奨案のまま進める」は、本レビュー時点の推奨案どおりADR化せず実装時に適用してよいという意味（要確認事項#1〜#10側の回答で「A1〜A20はあなたの推奨のまま進めてよい」と明示されたことによる）。

### 曖昧点（A1〜A20）

| ID | 決着状況 |
|---|---|
| A1 | 未決着。ULID生成ライブラリの具体選定は実装時の技術選定とし、ADR化不要。推奨案のまま進める |
| A2 | **[ADR-0009](adr/ADR-0009-tokenizer-estimation-strategy.md) / [ADR-0010](adr/ADR-0010-adapter-spi-estimate-tokens-method.md) で決着** |
| A3 | **[ADR-0001](adr/ADR-0001-datastore-selection.md) で決着**（Cache Store技術方針） |
| A4 | 未決着。Idempotency-Key自動生成アルゴリズムは推奨案のまま進める |
| A5 | 未決着。Canary抽選のハッシュ関数は推奨案のまま進める |
| A6 | **[ADR-0006](adr/ADR-0006-region-code-table-config-driven.md) で方針決着**（設定駆動化）。実際に対応する地域（実値）はビジネス判断待ちで未定 |
| A7 | 未決着。Prompt Validationの禁止パターン具体は推奨案のまま進める |
| A8 | **[ADR-0005](adr/ADR-0005-pii-masking-early-implementation.md) で方針決着**（先行実装可、既定OFF死守）。具体的な正規表現パターンは実装時に定義 |
| A9 | 未決着。Context超過時に自動リトライしない、という推奨案のまま進める |
| A10 | **[ADR-0011](adr/ADR-0011-structured-output-correction-retry-budget.md) で決着** |
| A11 | **[ADR-0012](adr/ADR-0012-cache-hit-quota-ratelimit-handling.md) で決着** |
| A12 | 未決着。`timeoutBudget`のStreaming/Non-Streaming切替は推奨案のまま進める |
| A13 | 未決着。Health Check probe失敗とパッシブヘルスの合算方式（OR条件）は推奨案のまま進める |
| A14 | 未決着。`PolicyRule.override`へのしきい値追加は推奨案のまま進める |
| A15 | **[ADR-0007](adr/ADR-0007-batch-retention-terminal-at.md) で決着** |
| A16 | 未決着。DRAINING強制終了ポリシーは推奨案のまま進める。運用インパクトがあるため実装時に再確認の余地あり |
| A17 | 未決着。Sessionスライディング更新は推奨案のまま進める |
| A18 | 未決着。Plugin scanトリガーは推奨案のまま進める |
| A19 | 未決着。設定スキーマ全体・GitOps対象範囲は推奨案のまま進める（ADR-0004で認証部分の設定キーのみ一部具体化） |
| A20 | 未決着。将来Capability（video_generation, fine_tuning）のスコープ外化は推奨案のまま進める |

### 矛盾・不整合（C1〜C7）

| ID | 決着状況 |
|---|---|
| C1 | **[ADR-0008](adr/ADR-0008-credential-ref-four-state-model.md) で決着**（9.7の4状態を正とする） |
| C2 | **未決着。** Quota予約の`commit()`/解放呼出しが`DefaultExecutionEngine.execute()`疑似コードに欠落している問題は、今回の回答（#1〜#10）で明示的に扱われていない。**P2ではなくP5（実行エンジン: Retry/Fallback/CB/RateLimit/Streaming）**着手時に別途ADRを起票する前提で保留する（訂正: 当初「P2」と記載していたが、Quota予約のcommit/releaseはExecution Engine全体ではなくRetry/Fallback/Streamingの制御フローと不可分であり、P5の対象範囲と一致するため）。実装方針の仮案: 成功パスで`commit(reservation, actualUsage)`を呼び、失敗パスでは`Reservation`をTTL失効に任せるか明示的に解放する。P5設計時に含めるべき事項は本セクション末尾の「P5設計メモ」を参照 |
| C3 | 未決着（低優先度）。設計書3.1のパッケージ構成と実リポジトリ構成（`gateway/apap-gateway`が`modules/`外）の相違は、`01_CLAUDE.md`が実勢を反映しており実装をブロックしない。必要なら別途軽量ADRを起票する |
| C4 | 未決着（低優先度）。`apap-runtime`/`apap-testkit`は設計書3.1に記載がないが、`01_CLAUDE.md`で存在が追認されており実装をブロックしない |
| C5 | 未決着（低優先度）。設計書3.10の`ApapFacade`とCLAUDE.mdの`ApapEngine`/`ApapEngineBuilder`が同一物を指すことの明記がない。用語整理として別途対応 |
| C6 | 未決着。A12と同一論点。推奨案（実行モードに応じた`timeoutBudget`既定値切替）のまま進める |
| C7 | 未決着。`QUOTA_EXCEEDED`(429, retryable=false)と`Retry-After`ヘッダ付与(13.5)の関係は仕様上矛盾ではないが、実装時のレスポンス生成で一貫性に注意。推奨案のまま進める |

### 設計書に定義がないが実装に必須な要素（U1〜U17）

| ID | 決着状況 |
|---|---|
| U1 | 未決着（低優先度）。`Clock` InterfaceはCLAUDE.mdでPort化方針のみ明示され、設計書内定義はないが実装規約により実装はブロックされない |
| U2 | 未決着（低優先度）。`IdGenerator`も同様 |
| U3 | 未決着。宣言的設定の完全スキーマはADR-0004で認証部分のみ一部具体化されたが全体像は未定 |
| U4 | 未決着。テストダブル命名規則・契約テスト項目は`apap-testkit`実装時に定める |
| U5 | **[ADR-0009](adr/ADR-0009-tokenizer-estimation-strategy.md) / [ADR-0010](adr/ADR-0010-adapter-spi-estimate-tokens-method.md) で決着** |
| U6 | **[ADR-0005](adr/ADR-0005-pii-masking-early-implementation.md) で方針決着** |
| U7 | **[ADR-0006](adr/ADR-0006-region-code-table-config-driven.md) で方針決着** |
| U8 | **[ADR-0003](adr/ADR-0003-initial-transport-protocol-scope.md) で決着**（gRPCは将来対応） |
| U9 | **[ADR-0003](adr/ADR-0003-initial-transport-protocol-scope.md) で決着**（WebSocketは見送り） |
| U10 | **[ADR-0003](adr/ADR-0003-initial-transport-protocol-scope.md) で決着**（MQTT Bridgeは対象外） |
| U11 | 未決着。Idempotency-Key自動生成アルゴリズムは推奨案のまま進める（A4と同一論点） |
| U12 | **[ADR-0001](adr/ADR-0001-datastore-selection.md) で決着** |
| U13 | **[ADR-0002](adr/ADR-0002-secret-store-responsibility-boundary.md) で決着** |
| U14 | **[ADR-0001](adr/ADR-0001-datastore-selection.md) で決着** |
| U15 | **[ADR-0004](adr/ADR-0004-ciap-claim-mapping-config-driven.md) で決着**（設定駆動化。実値はCIAP側と合意待ち） |
| U16 | 未決着。C2と同一論点。**P2ではなくP5**着手時に別途ADR化（訂正、理由はC2参照） |
| U17 | 未決着（低優先度）。Health Check probeの「軽量疎通」の内容は15章（Provider追加手順、本レビューでは未通読）との整合確認が必要 |

### P5設計メモ（C2 / U16: Quota予約のcommit/releaseライフサイクル）

P5（実行エンジン: Retry/Fallback/CB/RateLimit/Streaming）で `QuotaManager.checkAndReserve()` の予約ライフサイクルを設計する際は、以下を必ず含めること。

- **Streaming中断時**は、受信済み分（実際に消費されたトークン等）で `commit()` する。`release()`（全額解放）でも失敗時と同様の全額 `commit()`（過大計上）でもない、部分的な実績ベースのcommitとすること。
- **Cache短絡時**（`DefaultExecutionEngine.execute()` のキャッシュヒット分岐）は、予約自体を行わないか、行った場合は即座に `release()` する。ADR-0012（キャッシュ短絡時のQuota/RateLimit扱い: Provider呼出コスト0のためトークン消費はカウントしないがテナント流量としてのRate Limitは消費する）と整合させること。
- プロセスクラッシュ等で `commit()`/`release()` のどちらも呼ばれず**settleされなかった予約のTTL失効**を設けること。TTLがないと予約が滞留し、テナントがQuota枯渇で締め出される障害モードになる。

---

## 5. 総括

- 全体として2〜4章・9・10・12〜14章は数値・状態遷移・エラー体系まで具体的に定義されており、実装の骨格を組む上での曖昧さは比較的少ない。
- 「外部技術選定（DB/Secret Store/Cache Store）」「外部システム結合仕様（CIAP/gRPC/WebSocket/MQTT）」「コンプライアンス関連（PIIマスキング/データ保持）」「Structured Output是正リトライ/CredentialRef状態モデルの矛盾」に関する要確認事項#1〜#10は、2026-08-20の回答を受けて [ADR-0001〜ADR-0013](adr/README.md) として決着済み。実装はブロックされていない。
- 未決着のまま残る項目（C2/U16のQuota予約commit/release欠落、C3〜C5の設計書とリポジトリ実態の軽微な乖離、A1・A4・A5・A7・A9・A12〜A14・A16〜A20・U1〜U4・U11・U17）は、いずれも低リスクかつ推奨案どおり進めてよいものか、実装フェーズで個別ADR化すべきものとして4章の対応表に整理済み。P1（プロジェクト骨格構築）の着手をブロックする項目はない。
- 05〜08, 11章（シーケンス/クラス図/コンポーネント図/パッケージ図/デプロイメント図）は見出しレベルの確認に留めているため、実装フェーズでモジュール別の詳細実装に入る際に都度通読する。特にU17（Health Check probe内容）は15章（Provider追加手順、本レビューでは未通読）との整合確認が必要。
