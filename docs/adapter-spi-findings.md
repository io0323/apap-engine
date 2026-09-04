# ProviderAdapter SPI 検証結果（実Provider向けAdapter 第1号）

実在するAI Provider向けの `ProviderAdapter` を1つ実装し、**SPIが実APIとの接触に耐えるか**を検証した記録。
Adapterそのものではなく、この文書が本フェーズの主成果物である。

対象Adapter: `adapters/adapter-anthropic/`（製品名・API仕様の記述は同ディレクトリ内に閉じている）

---

## 0. この文書の信頼度 —— 必ず先に読むこと

**実APIへの接続は行っていない。** 記録・再生に使っているフィクスチャは
公開API仕様に基づく**手書き**であり、実通信の記録ではない。各記録ファイルの `source` が
それを宣言し、`RecordingProvenanceTest` が全件に宣言があることを機械検証している。

したがって本文書の各項目は次の2種類に分かれる。読み分けを誤ると「検証済み」の範囲を過大評価する。

| 印 | 意味 |
|---|---|
| **[構造]** | SPIの型・シグネチャに対する判断。実APIに接続しなくても結論が変わらない（例: 必須パラメタを表現できるか、pull型に写せるか） |
| **[要実測]** | 実APIの挙動に依存し、手書きフィクスチャでは確かめきれない（例: 実際のエラーボディ、切断時の振る舞い、レイテンシ） |

実記録への差し替え手順は `adapters/adapter-anthropic/README.md` を参照。差し替え後、
**[要実測]の項目を再評価すること**。

---

## 1. 総括

| # | 観点 | 判定 |
|---|---|---|
| 1 | Streaming（pull型への写像・バックプレッシャ） | **問題なし** |
| 2 | Tool Calling（ADR-0019 の `toolCallComplete`） | **問題なし**（明示シグナルを供給できた） |
| 3 | エラー分類（8分類・Retry-After） | **回避策で対応** + **SPI変更が必要**（CONTENT_FILTERED） |
| 4 | Usage（取得タイミング・推定フォールバック） | **問題なし** |
| 5 | 認証（auth_type抽象・Credential参照） | **SPI変更が必要**（参照解決の口が無い） |
| 6 | Model Discovery | **回避策で対応** |
| 7 | ContentPart（画像・音声） | **回避策で対応** + 一部 **SPI変更が必要** |
| 8 | capabilityConstraints の表現力 | **回避策で対応** |
| 9 | 生成パラメタ（`max_tokens` 必須・`seed` 非対応） | **SPI変更が必要** |
| 10 | typealias 経由のネスト型参照 | **回避策で対応**（SPI側に既に用意があった） |

SPI変更が必要と判断したのは4件（§3.3 / §3.5 / §3.7 / §3.9）。
**いずれも実装は次フェーズとし、本フェーズでは一覧化に留める。**
「このProviderが特殊なだけ」と判断して**SPIを変えない**と決めたものは §4 に分けて記載した。

---

## 2. 問題なしと確認した項目

### 2.1 Streaming: pull型 `AdapterStream` への写像 **[構造]**

SSEのイベント列を `suspend fun next(): AdapterChunk?` へ**無理なく写せた**。

- 実APIのSSEは `message_start` / `content_block_start` / `content_block_delta` /
  `content_block_stop` / `message_delta` / `message_stop` / `ping` / `error` の型付きイベント列。
  これを `AdapterChunkType` の7種へ写す（対応表は `AnthropicAdapterStream` のKDoc）。
- **1イベント → 複数チャンク**になる箇所がある（`message_stop` が USAGE と MESSAGE_END の
  2つを生む）。`next()` は1回1チャンクなので内部キューを1本持って吸収した。
  SPIの形を変える必要はなかったが、**pull型SPIを実装する側は必ずこのキューが要る**ので、
  Adapter実装ガイドに書く価値がある。
- 逆に**複数イベント → 0チャンク**もある（`content_block_start` のtextブロックなど）。
  `next()` がイベントを読み進めながらチャンクが出るまでループする形で自然に書けた。

バックプレッシャ **[要実測]**: 設計としては意図通り効く。`KtorHttpTransport` は
`Channel.RENDEZVOUS` を使っており、下流が `next()` を呼ぶまで送信側が進まないため、
SSE読み取りが止まればTCPの読み取りも止まる。ただし**実ネットワーク越しの実測はしていない**。
再生では `ReplayEventStream` が同期的にキューから返すため、バックプレッシャの検証にはならない。

### 2.2 Tool Calling: ADR-0019 の `toolCallComplete` **[構造]**

**明示シグナルを実ストリームから供給できた。** ブレース数え上げのフォールバックは
このProviderでは不要。

- `content_block_stop`（index付き）が「このtool_useブロックの引数はここで完結した」を
  そのまま意味する。これを `toolCallComplete = true` として流している。
- 引数は `input_json_delta.partial_json` の断片で届き、連結すると完全なJSONになる。
  `AnthropicAdapterReplayTest` が「デルタ連結が妥当なJSONになること」「完了シグナルがちょうど1回」を検証。
- `input_json_delta` は `id`/`name` を持たない（`content_block_start` にしか無い）ため、
  Adapterは **index → ToolCall の対応表**を持つ必要がある。SPIの `AdapterChunk.toolCallDelta`
  は毎回 `callId`/`toolName` を要求する（`ToolCall` の `init` が空文字を弾く）ので、
  この対応表なしには TOOL_CALL_DELTA を組み立てられない。**SPIの問題ではないが、
  実装ガイドに書かないと各Adapterが同じ罠を踏む。**

ADR-0019の判断は**このProviderに関しては正しかった**と言える。ただし
「明示シグナルを持たないProvider」での有効性は、そのProviderで実装するまで未検証のまま。

### 2.3 Usage の取得 **[構造]**

取得タイミングは**応答内（非Streaming）とストリーム末尾（Streaming）の両方**で、
いずれもSPIの型で表現できた。

- 非Streaming: 応答本文の `usage` に `input_tokens` / `output_tokens`。
- Streaming: **2箇所に分かれて届く**。`message_start` に `input_tokens`、
  `message_delta` に最終 `output_tokens`。Adapterが両方を保持して `message_stop` の時点で
  USAGEチャンクを1つ組み立てる。`Usage` は不変VOで `totalTokens == input + output` を
  検証するため、**途中で片方だけを載せたUsageは作れない**。この制約はむしろ正しく働いた
  （中途半端なUsageがコアへ流れない）。
- `Usage.estimated` フラグがあるおかげで、Provider実測値（false）と推定値（true）を
  区別して返せた。推定へのフォールバックはコア側（ADR-0009のHEURISTIC、安全マージン15%）が
  持っており、Adapterが `estimateTokens` を実装しない選択（null返却）で自動的にそちらへ倒れる。
  この二段構えは機能している。
- `cache_read_input_tokens` は `Usage.cachedTokens` へ写せた。

---

## 3. SPI変更が必要／回避策で対応した項目

### 3.1 エラー分類: 8分類への写像 **[構造]** / 実ボディの確認 **[要実測]**

**8分類のうち7つは一意に写せた。** 写像表は `ErrorMapper` のKDocに記載。

写像の設計上の注意として、**HTTPステータスだけでは足りない**。403（permission_error）は
AUTH_ERROR、404（not_found_error）は MODEL_ERROR とすべきで、ステータスの数値だけを見ると
どちらも「4xxだからINVALID_REQUEST」に落ちる。そこで `error.type` を先に見て、
読めない場合のみステータスで判断する二段構えにした。

Retry-After **[構造]**: 429応答の `retry-after` ヘッダを `AdapterException.retryAfter` へ
渡せた。ヘッダがHTTP-date形式の場合は**nullにする**（誤って0秒と解釈して即再試行しないため）。
実APIが実際に何を返すかは **[要実測]**。

#### **SPI変更が必要**: CONTENT_FILTERED が例外として表現できない

実APIはコンテンツ拒否を**エラーではなく HTTP 200 の応答**として返す
（`stop_reason: "refusal"`）。したがって:

- `AdapterErrorCategory.CONTENT_FILTERED` を投げる経路が**存在しない**
- `AdapterContractTest` の `category=CONTENT_FILTERED` は**唯一パスできない項目**として
  スキップになっている（他14項目は全て実行・パス）

これは**Adapterの実装不足ではなくSPIの設計問題**である。SPIには
`AdapterErrorCategory.CONTENT_FILTERED`（例外側）と `FinishReason.CONTENT_FILTERED`（応答側）が
両方あるのに、両者の関係が定義されていない。コア側が「拒否された」を一貫して扱うには、
どちらで来ても同じ扱いになる保証が要る。

- 現状のAdapterの挙動: `FinishReason.CONTENT_FILTERED` を持つ**正常応答**として返す
- 影響しうる要件: FR-CAP-003 周辺（拒否時の扱い）、2.11のRetry/Fallback判断
  （拒否は再試行しても直らないので、Fallbackで別Providerへ回すべきかの判断が必要）
- 提案: `AdapterContractTest` に「CONTENT_FILTEREDは例外・応答のどちらで表現してもよいが、
  どちらかであることを宣言する」フックを設ける。または応答側へ一本化する

→ **ADR-0037 起票（実装は次フェーズ）**

### 3.2 Model Discovery **[要実測]**

モデル一覧APIから**一覧の取得はできた**（再生では確認済み、実APIでは未確認）。

**回避策で対応**: 一覧APIは `id` と表示名しか返さず、`DiscoveredModel` が必須で要求する
`contextWindow` / `maxOutputTokens` を**返さない**。現状はAdapter内の既定値
（`DEFAULT_CONTEXT_WINDOW` / `DEFAULT_MAX_OUTPUT_TOKENS`）で埋めている。

- `DiscoveredModel` の `contextWindow`/`maxOutputTokens` は `require(> 0)` の必須フィールド
- 「Providerが返さない」を表現できないため、**Adapterが嘘の数字を入れるしかない**
- 影響: 15.1 Step4-6「discoverModels結果を承認 or 手動登録」で、承認者は
  Adapterが埋めた既定値を実際の値だと誤読しうる
- 判断: これは**このProviderが特殊なわけではない**（一覧APIがスペックを返さないProviderは多い）。
  ただし SPI変更（null許容化）は `Model` アグリゲート側の必須性にも波及するため、
  影響範囲を測ってからにすべき。今回は回避策に留め、ADRは起票しない

### 3.3 認証: Credential参照の解決 **[構造]**

#### **SPI変更が必要**: Adapterがどの `CredentialRef` を使うべきか分からない

- `AdapterConfig`（`initialize` に渡る）は `providerId` / `endpoints` / `rateLimits` /
  `regions` / `options` しか持たず、**`CredentialRef` を持たない**
- `authenticate()` は**引数を取らない**
- 一方 `SecretAccessor.resolve(ref)` は `CredentialRef` を要求する

つまりAdapterは自力で参照名を決めるしかない。`adapter-mock` が
「実Providerと違い、AdapterConfigはどのCredentialRefを使うかを明示しないため、
このAdapter自身が１つ保持する」というKDocとともに固定のダミー参照を持っているのは、
**この欠落の既知の兆候**だった。実Adapterでも同じ壁に当たった。

- 現状の回避策: `AdapterConfig.options["credential.ref"]`（既定 `anthropic-api-key`）から
  `CredentialRef` を組み立てる。`options` は自由なMapなので型の助けがない
- 影響しうる要件: FR-SEC-002（Credential Rotation）。ローテーション中は
  ACTIVE と STANDBY の2本が並存する（ADR-0008の4状態モデル）が、Adapterは
  **どちらを使うべきかを知る手段がない**。`options` 経由の文字列では版の切替を表現できない
- 提案: `AdapterConfig` に `credentialRefs: List<CredentialRef>` を追加する、
  または `authenticate(ref: CredentialRef)` へ変更する

→ **ADR-0038 起票（実装は次フェーズ）**

**auth_type 抽象そのものは足りていた** [構造]。APIキー方式は「呼出ごとにヘッダを組む」だけで、
`AuthContext` に何も載せずに済んだ。むしろ `AuthContext` に鍵を載せると `execute` まで
Credentialが持ち回られて不変条件4に反するため、**空のAuthContextを返すのが正しい**。
トークン更新が要る方式（OAuth2等）では `AuthContext.expiresAt` が効くはずだが、
このProviderでは使わないため**未検証**。

リージョン別エンドポイント **[構造]**: 実APIは単一のグローバルエンドポイントのため、
`AdapterConfig.endpoints` から重み最大のものを選ぶだけで足りた。
複数リージョンの振り分けは**このProviderでは検証できていない**。
なお `AdapterRequest` はリージョンのヒントを持たないので、リージョン選択が要るProviderでは
Adapterが独自に決めるしかない（要検証項目として残す）。

### 3.4 ContentPart: 画像・音声 **[構造]**

- **画像**: 表現できた。`ContentPart.Image(uri, mimeType)` を、`data:` スキームなら
  base64形式、それ以外はURL形式の `source` オブジェクトへ振り分ける。**回避策で対応**
  （SPIは「URI」としか言っておらず、data URIか外部URLかの区別が型に無いため、
  Adapterが文字列のプレフィックスで判定している）
- **音声・動画**: **表現できない**。Messages APIに対応するcontent blockが無い。
  現状は `AdapterErrorCategory.UNSUPPORTED_CAPABILITY` で明示的に拒否している
  （黙って落とすと、利用側は音声を送ったつもりでテキストだけが処理される）

#### **SPI変更が必要**: modality対応可否を事前に申告できない

`capabilityConstraints` には対応modalityを申告するフィールドが無い。そのため
利用側は**送ってみるまで**音声が使えないと分からない。現状は `extra` マップに
`modalities.input=text,image` を入れているが、`extra` は `Map<String,String>` の
自由領域なのでコアは解釈できず、Routingの候補選択にも使えない。

- 影響しうる要件: FR-RTE-002（Capabilityに基づく候補選択）。音声入力を含むリクエストが
  音声非対応のProviderへルーティングされ、実行時に初めて失敗する
- 提案: `CapabilityConstraints` に `supportedInputModalities: Set<Modality>` を追加

→ **ADR-0039 起票（実装は次フェーズ）**

### 3.5 生成パラメタ **[構造]**

#### **SPI変更が必要**: 必須パラメタを表現できない／`seed` が捨てられる

- **`max_tokens` は実APIで必須**だが、SPIの `GenerationParams.maxTokens` は `Int?`（任意）。
  未指定時にAdapterが既定値（4096）を捏造している。**本来はModelの `maxOutputTokens` を
  使うべきだが、その値はAdapterへ渡らない**（`AdapterRequest` は `modelName` しか持たない）。
  結果、Model定義で 8192 と登録していても、リクエストが `maxTokens` を省略すると
  Adapterの既定値 4096 で頭打ちになる——**設定と実挙動が静かに食い違う**
- **`seed` は実APIに対応するパラメタが無い**。現状は黙って捨てている。
  再現性を期待した利用側は、それが効いていないことに気付けない

提案: (a) `AdapterRequest` にModelの上限（`maxOutputTokens`）を渡す、
(b) 未対応パラメタをAdapterが申告できるようにする（`capabilityConstraints` 経由など）。

→ **ADR-0040 起票（実装は次フェーズ）**

### 3.6 capabilityConstraints の表現力 **[構造]**

**回避策で対応**。固定フィールド（`maxInputTokens` / `maxOutputTokens` / `streamable` /
`supportsTools`）で表せない制約が実際に複数出た。

| 表現できなかった制約 | 現状の逃がし方 |
|---|---|
| メッセージが user/assistant 交互でなければならない | `extra["messages.must_alternate"]` |
| `max_tokens` が必須 | `extra["max_tokens.required"]` |
| 入出力modality | `extra["modalities.input"]` |

`extra: Map<String,String>` があるおかげで**情報を落とさずに済んだ**のは良い設計だが、
コア側はこれを解釈しないため、実質「人間向けメモ」に留まる。
なお交互制約はAdapter側で正規化して吸収した（§4.1）ので、これ自体は問題化していない。

### 3.7 typealias 経由のネスト型参照 **[構造]**

**回避策で対応**（SPI側に既に用意があった）。Kotlinは**typealias経由でネストした型に
アクセスできない**ため、`ContentPart.Text` と書くとコンパイルエラーになる。
SPIは `TextContentPart` / `ImageContentPart` … のフラットなaliasを別途用意しており、
そちらを使えば解決する。

ADR-0016が「typealiasはソースレベルの分離のみを提供する」と述べているとおりの制約で、
**SPIの不足ではない**。ただし新規Adapter作者は必ず一度踏むので、
15章のAdapter開発手順に一行あると良い。

---

## 4. 「このProviderが特殊なだけ」と判断し、SPIを変えなかったもの

1つのProviderに合わせてSPIを歪めないため、以下は**Adapter内の写像で吸収**した。

### 4.1 system の巻き上げ・role の交互化・tool_result の位置

- 実APIは `system` を messages の外のトップレベルパラメタとして受け取る
- messages は user/assistant が交互で、user 始まりでなければならない
- `tool_result` は user メッセージ内の content block として渡す

いずれも**SPIの `messages`（role付き）に情報は足りており**、Adapter側の写像で完全に吸収できた
（`RequestBodyBuilder`）。SPIをこのProviderの形（system別枠・交互強制）に寄せると、
その形を持たない他のProviderのAdapterが逆に書きにくくなる。**SPIは変えない。**

`AnthropicAdapterReplayTest` が、巻き上げ・マージ・先頭補完・tool_result合成の
4つを実際の送信ボディに対して検証している。

### 4.2 `fetchUsage` / `fetchCost` が呼べない

使用量・コスト集計APIは存在するが、**通常のAPIキーとは別の管理用Credential**を要する。
現在のSPIはProviderあたり1本の参照しか扱えない（§3.3）ため、呼べない。
**`null` を返す**（15.1 Step1「Provider側API未提供のAdapterはnullを返す」）。

これは §3.3 のCredential参照問題の派生であり、独立したSPI変更としては起票しない。
§3.3 が解決すれば自然に実装できる。

### 4.3 `estimateTokens` を実装しない判断

トークン数計算APIは存在するが**ネットワーク往復を伴う**。ADR-0010は
「正確なトークナイザを提供できる場合のみ実装する」としており、見積りのたびに
Provider呼出が増える影響（レイテンシ・レート制限消費・障害時の縮退）が大きいため
実装せず `null` を返し、コア側のHEURISTIC推定（ADR-0009、安全マージン15%）へ委ねた。

`AdapterContractTest` の該当項目は「null または非負」を許容しており、この判断を通せる。
**SPIの問題ではない**が、「ネットワークを伴う正確な推定」を選べるようにするかは
将来の論点として残る（実装すればレイテンシ、しなければ精度を失う）。

### 4.4 `AdapterChunk.index` の意味が未定義

SPIは `index >= 0` としか定めておらず、**チャンク通番**なのか**content blockの位置**なのかが
決まっていない。`adapter-mock` は通番で使っており、それに合わせた。
実APIのSSEは content block の index を持つため、素直に写すと別の意味になる。

Adapter間で意味が食い違うと、コア側が index を使い始めた時点で壊れる。
現状コアは index を読んでいないため実害はない。**SPI変更は起票せず、
定義を明文化すべき点として記録に留める。**

---

## 5. Contract Test の結果

`AnthropicAdapterContractTest`（`AdapterContractTest` を継承）: **15項目中14項目パス、1項目スキップ**。

任意フック（`errorRequestFor` / `secretProbeValue` / `timeoutExceedingRequest` /
`streamRequest` / `unsupportedCapabilityRequest`）は**すべて実装した**。未実装だと
`Assumptions` で静かにスキップされ、緑のまま「検証していない」状態になるため。

| 項目 | 結果 |
|---|---|
| supportedCapabilities と execute の整合 | パス |
| 申告外Capabilityが UNSUPPORTED_CAPABILITY | パス |
| エラー分類 TRANSIENT / RATE_LIMITED / INVALID_REQUEST / AUTH_ERROR / MODEL_ERROR / PROVIDER_UNAVAILABLE / UNSUPPORTED_CAPABILITY | パス（7分類） |
| エラー分類 CONTENT_FILTERED | **スキップ（SPIの設計問題。§3.1）** |
| `AdapterRequest.timeout` の遵守 | パス |
| `cancel()` 後にチャンクが流れない | パス |
| Credentialが例外・標準出力に現れない | パス |
| healthCheck の応答時間 | パス |
| estimateTokens が null か非負 | パス |

唯一の未パスは **SPIの設計問題**であり、Adapterの実装不足ではない（§3.1参照）。

Credential非漏出は Contract Test に加えて `AnthropicAdapterReplayTest` でも検証している
（ヘッダには入るがボディには入らないこと、`HttpCall.toString()` が値を出さないこと）。

---

## 6. 15.4 Go-Liveチェックリストに対する現状評価

| # | 項目 | 現状 | 根拠・残作業 |
|---|---|---|---|
| 1 | Contract Test全件パス（エラー分類・Stream中断・Credential非漏出含む） | **△** | 15項目中14パス。CONTENT_FILTEREDのみ未パスで、原因はSPI設計（§3.1）。実APIでの再確認も要る |
| 2 | Health Check応答が30秒周期で安定 | **未評価** | `healthCheck` は実装済み（モデル一覧で代用）。30秒周期の安定性は実API＋常駐運用でしか測れない。周期実行は宿主が回す（ADR-0032） |
| 3 | 単価（PriceBook）登録済・コスト算出がAuditへ反映 | **未実施** | Adapterの範囲外。Model登録時にPriceBookへ単価を入れる運用手順（ADR-0021: 単価未登録Modelは候補から除外される） |
| 4 | Fallback Chainに組み込んだ場合の切替動作確認（強制障害試験） | **△** | エンジン側は `SequenceFlowE2ETest` / `RequestFidelityE2ETest` で検証済み。**この実Adapterを組み込んだ状態での試験は未実施** |
| 5 | Rate Limit設定がProvider実制限以下 | **未実施** | 実アカウントの制限値を確認して `Provider.rateLimits` に設定する必要がある。P12以降 `rpm` はRateLimiterへ反映される（`tpm`/`concurrent` は未反映＝F12） |
| 6 | Canary 5%で24時間、エラー率・レイテンシがSLO内 | **未実施** | 実運用フェーズ。Alias weightでの流量制御は実装済み |
| 7 | ロールバック手順（Alias weight 0%化）の演習済 | **未実施** | `assignAlias` で weight を変更できることはE2Eで確認済みだが、運用演習は未実施 |

**結論: Go-Live可否は「まだ不可」。** 1（CONTENT_FILTERED）と、そもそも
**実APIに一度も接続していないこと**が最大のブロッカー。2・5・6・7は実アカウントと
常駐運用が前提で、リポジトリ内では判定できない。

---

## 7. 次フェーズへの申し送り（ADR起票一覧）

| ADR | 論点 | 影響しうる要件 |
|---|---|---|
| ADR-0037 | CONTENT_FILTERED を例外側と応答側のどちらで表現するか | FR-CAP-003、2.11 |
| ADR-0038 | AdapterがCredentialRefを解決する手段（`AdapterConfig` かシグネチャか） | FR-SEC-002 |
| ADR-0039 | modality対応可否の申告（Routing候補選択に使えるように） | FR-RTE-002 |
| ADR-0040 | 必須パラメタ・未対応パラメタの扱い（`max_tokens` / `seed`） | FR-CAP-001、FR-EXE-002 |

**実装は次フェーズ。** 本フェーズでは一覧化に留め、SPIには手を入れていない。
1つのProviderの都合でSPIを変えると、2つ目のProviderで必ず歪みが出るため、
できれば**2つ目のAdapterを別Providerで書いてから**これらを確定させたい。

## 8. 併せて是正した検査の不備

`VendorNeutralityTest` の走査ルートに `adapters` が含まれており、
**実Provider向けAdapterを1つでも足すと必ず落ちる**状態だった。不変条件1自身が
「実Provider固有の知識は `adapters/` 配下にのみ存在してよい」と定めているにもかかわらず、
検査がそれを許していなかった。実Adapterが1件も無かったため表面化していなかっただけである。

`config/vendor-neutrality/vendor-specific-adapters.txt` に**そのAdapterだけ**を
理由付きで登録する方式へ変更した（`adapters/` を丸ごと除外すると adapter-mock まで
無検査になり、「コアのテストは adapter-mock のみを使う」の担保が消える）。

違反注入による確認（不変条件9）:

| 注入 | 結果 |
|---|---|
| 例外リストに無いAdapter（adapter-mock）に製品名を書く | 失敗（違反として検出） |
| `modules/apap-runtime` を例外に登録しようとする | 失敗（adapters/直下のみ許可） |
| 例外エントリの理由を空にする | 失敗（理由必須） |
| 実在しないパスを例外に登録する | 失敗（残骸の検出） |
