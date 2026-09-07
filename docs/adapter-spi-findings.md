# ProviderAdapter SPI 検証結果（実Provider向けAdapter 第1号）

実在するAI Provider向けの `ProviderAdapter` を1つ実装し、**SPIが実APIとの接触に耐えるか**を検証した記録。
Adapterそのものではなく、この文書が本フェーズの主成果物である。

対象Adapter: `adapters/adapter-anthropic/`（製品名・API仕様の記述は同ディレクトリ内に閉じている）

---

## 0. この文書の信頼度 —— 必ず先に読むこと

**実APIへの接続は行っていない。** 記録・再生に使っているフィクスチャは
公開API仕様に基づく**手書き**であり、実通信の記録ではない。各記録ファイルの `source` が
それを宣言し、`RecordingProvenanceTest` が全件に宣言があることを機械検証している。

### 0.1 実測を見送った判断（P16）

P16の着手時点で実API検証（作業1）を**見送った**。理由と、その判断が成り立つ範囲を記す。

**見送ってよいと判断した理由**: [構造]の項目だけで**7件のSPI不足が特定でき**、
それらは実APIの挙動に依存しない（型・シグネチャに何が無いかの問題であり、
Providerが何を返すかとは独立している）。したがってSPI変更を実測より先行できる。
実測を待つと、複数Adapterが存在しない「変更コストが最も低い時期」を逃す。

**その代わり [要実測] は未確定のまま残る。** 実測でしか分からない事柄は依然として不明で、
本文書の [要実測] 印はすべて有効である。印と `RecordingProvenanceTest` は**維持する**——
時間が経つと「一通り検証した」という記憶に置き換わるため、この印が唯一の防止手段である。

### 0.2 実測が必須になる条件

次のいずれかに到達したら、**実測を済ませるまで先へ進まない**。

1. **prompt-engine が実Providerへ接続する前**
2. **実トラフィックを流す前**（Canary 5%を含む。少量でも実トラフィックは実トラフィックである）

実測手順は `adapters/adapter-anthropic/README.md`。`LiveProviderTest` は
環境変数でのみ有効化され、CIでは実行されない。鍵は `SecretStore` 経由で解決し、
実行後に**実際の鍵文字列**が記録・計測ファイルへ現れないことを `@AfterAll` で検査する。

### 0.3 未検証であることの可視化

構造的にはスタブと同じ（動いて見えるが実挙動は未確認）であるため、
本リポジトリがスタブに与えてきた扱い（`NoOpQueryEmbedder`の明示的opt-inとWARN、
`ZeroCostEstimator.isStub`の自己申告）と同じ扱いを与えた。

| 仕掛け | 何を防ぐか |
|---|---|
| `UnverifiedAgainstLiveApi.warnIfUnverified()` を `initialize()` で呼ぶ | 未検証のAdapterが本番配線に載ったことがログに必ず出る |
| `UnverifiedAgainstLiveApiTest` | `LIVE_VERIFIED=false` の間、記録が手書きのままであること・本文書が未検証である旨と`[要実測]`印を保持していることを機械検証する |
| `RecordingProvenanceTest` | 各記録の出所宣言と機微情報の非混入 |

コードの申告・記録の出所・文書の記述の**3点が揃って初めて意味を持つ**ので、3点セットで縛っている。

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
「このProviderが特殊なだけ」と判断して**SPIを変えない**と決めたものは §4 に分けて記載した。

> **P16で実装済み。** 上記4件に加え、P16の調査で見つかった3件（`outputSchema`の不使用、
> Streamingの`finishReason`欠落、`capabilityConstraints`が誰にも読まれていない）を含む
> **7件をまとめて実装した**。実装結果と、その過程で新たに見つかった欠落は §9 を参照。
> §2〜§4 の記述はP15時点の調査結果としてそのまま残す（判断の経緯を消さないため）。

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

## 6. 15.4 Go-Liveチェックリストに対する現状評価（P16再評価）

| # | 項目 | P15 | P16 | 根拠・残作業 |
|---|---|---|---|---|
| 1 | Contract Test全件パス（エラー分類・Stream中断・Credential非漏出含む） | △ | **○（機能面）** | **16/16パス・スキップ0**（CONTENT_FILTEREDを含む）。ADR-0037で申告方式にしたことで、再現できない項目を黙って飛ばせなくなった。ただし**実APIに対しては未実行**のため、実挙動での合格は未確認 |
| 2 | Health Check応答が30秒周期で安定 | 未評価 | **未評価** | 実API＋常駐運用でしか測れない。`LiveProviderTest`に計測を用意済み（30秒予算の判定つき） |
| 3 | 単価（PriceBook）登録済・コスト算出がAuditへ反映 | 未実施 | **未実施** | Adapterの範囲外。Model登録時の運用手順 |
| 4 | Fallback Chainに組み込んだ場合の切替動作確認（強制障害試験） | △ | △ | エンジン側は検証済み。**この実Adapterを組み込んだ状態での試験は未実施** |
| 5 | Rate Limit設定がProvider実制限以下 | 未実施 | **未実施** | 実アカウントの制限値が要る |
| 6 | Canary 5%で24時間、エラー率・レイテンシがSLO内 | 未実施 | **未実施** | 実運用フェーズ。§0.2により、ここへ進む前に実測が必須 |
| 7 | ロールバック手順（Alias weight 0%化）の演習済 | 未実施 | **未実施** | 機構はE2Eで確認済み、運用演習は未実施 |

**結論: Go-Liveは引き続き「不可」。** P16でSPIの機能的な欠落は解消したが、
**ブロッカーの本体は変わっていない**——実APIに一度も接続していないこと。
加えてP16で新たに判明した ADR-0041（`initialize()`が本番のどこからも呼ばれていない）により、
**現状では実Providerを本番配線で動かすことすらできない**。Go-Liveの前提として、
少なくとも次の3つが要る。

1. ADR-0041の解決（Adapterが初期化される経路）
2. 実APIでの実測（§0.2の条件）
3. 上表 2 / 5 / 6 / 7 の運用側の確認

## 7. 次フェーズへの申し送り（ADR起票一覧）

| ADR | 論点 | 影響しうる要件 | P16での状態 |
|---|---|---|---|
| ADR-0037 | CONTENT_FILTERED を例外側と応答側のどちらで表現するか | FR-CAP-003、2.11 | **実装済**（申告方式） |
| ADR-0038 | AdapterがCredentialRefを解決する手段（`AdapterConfig` かシグネチャか） | FR-SEC-002 | **実装済**（`credentialRefs`。ただしADR-0041により未到達） |
| ADR-0039 | modality対応可否の申告（Routing候補選択に使えるように） | FR-RTE-002 | **実装済**（SPI・ドメイン・Routingの3層） |
| ADR-0040 | 必須パラメタ・未対応パラメタの扱い（`max_tokens` / `seed`） | FR-CAP-001、FR-EXE-002 | **実装済**（`outputSchema`の実使用も含む） |
| ADR-0028 | SSEの`message_end`が`finish_reason`を省略する | FR-CAP-004、13.3 | **Superseded**（P16で送出するようにした） |
| **ADR-0041** | `ProviderAdapter.initialize()` が本番のどこからも呼ばれていない | FR-PRV-001〜006、FR-SEC-002 | **未着手**（P16で新規検出） |

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

---

## 9. P16: SPI変更の実装結果

複数Adapterが存在しない今が変更コストの最も低い時期であるため、7件をまとめて実施した。
SPIは **1.0.0 → 1.1.0（マイナー）**。追加はすべて既定値付き・末尾配置で、
フィールド削除・型変更・制約強化は行っていない（ADR-0016）。版は `SpiSurface.version` の
単一管理とし、各Adapterの `spiVersion()` はそれを参照する（数値の書き写しをやめた）。

### 9.1 outputSchema をAdapterで実際に使う（最優先）

**何が起きていたか**: `outputSchema` はAdapterのmain配下で**参照ゼロ**だった。スキーマが
Providerへ渡らないため、モデルは構造の指示なしに生成し、毎回まず`AttemptExecutor`の
検証に落ちてからADR-0011の是正リトライで直る動作になっていた。是正機構は例外的救済であって
常用経路ではなく、往復2回ぶんのコストと成功率の両方に効いていた。

**ネイティブ機構の有無（出典を明示）**: 実APIには構造化出力のネイティブ機構が**ある**。

| 項目 | 内容 |
|---|---|
| パラメタ | `output_config.format` |
| 出典1 | `https://platform.claude.com/docs/en/api/messages` の Body Parameters > output_config（"specify a JSON schema for structured outputs"） |
| 出典2 | `https://platform.claude.com/docs/en/api/beta/messages` の `BetaOutputConfig.format`（型名 `BetaJSONOutputFormat`、"Schema for structured JSON output"） |
| 確認方法 | 公開ドキュメント（実APIでは未確認） |

**実装**: 既定で `output_config.format` を送る（`StructuredOutputMode.NATIVE`）。

**[要実測]**: **`format` オブジェクトの内側の正確な形は公開ドキュメントから確定できていない。**
型名が `BetaJSONOutputFormat` であることから `{"type":"json_schema","schema":{…}}` と実装したが、
形が違えば `invalid_request_error` でスキーマ付きリクエストが全滅する——これは
「スキーマが無視される」従来より悪い。そのため
`AdapterConfig.options["structured_output.mode"] = "prompt"` の1行で、スキーマを
systemプロンプトへ組み込む経路へ退避できるようにした（`prompt` は文字列を足すだけなので確実に動く）。
実APIで弾かれた場合はここを切り替えること。

**効果の検証**: `StructuredOutputTest` が、スキーマが送信ボディへ載ること・promptモードでは
systemへ組み込まれること・スキーマが届いた場合は**1回の呼出で完了し是正リトライが発生しないこと**を
確認する。ただし「モデルが実際にスキーマへ従う率」は実APIでしか測れない（**[要実測]**）。

### 9.2 「届いているが使われていない」項目の全数監査

P14のリクエスト忠実性検査は「Adapterへ**届く**こと」を検証したが、
「Adapterが**使う**こと」は別問題だった。全フィールドを監査した結果:

| フィールド | P15時点 | P16での対応 |
|---|---|---|
| `params.temperature` / `topP` / `stop` | 使用 | — |
| `params.maxTokens` | 使用 | Model上限へのフォールバックを追加（§9.5） |
| `params.seed` | **黙殺** | `UNSUPPORTED_CAPABILITY` で明示的に拒否（§9.5） |
| `outputSchema` | **黙殺** | ネイティブ機構／プロンプト組込で使用（§9.1） |
| `capabilityId` / `tools` / `toolResults` / `timeout` / `traceHeaders` / `messages` | 使用 | — |
| `input` | 未使用（意図的） | `messages` が正。SPIの意図どおりで問題なし |
| `authContext` | 未使用（意図的） | APIキー方式では呼出ごとにヘッダを組むため。Credentialを載せないのが正しい |

**黙殺は2件**（`seed` と `outputSchema`）で、いずれも解消した。

### 9.3 StreamChunk の finishReason（ADR-0028をSupersede）

13.3のSSE例は `event: message_end` / `data: {"finish_reason":"completed"}` と明記しているのに、
`StreamChunk`/`ApapStreamChunk` にフィールドすら無く、**`length_limit` で切られたストリームが
正常完了と区別できなかった**。`tool_call` / `content_filtered` も同様に届いていなかった。

Adapter → `StreamChunk` → `ApapStreamChunk` → Gateway SSE まで通した。
実装中に**終端チャンクの二重送出**も見つかった——Adapter由来のMESSAGE_ENDと実行エンジンが
終端で送るMESSAGE_ENDの両方が流れていた（従来テストは `chunks.last()` しか見ておらず気付けなかった）。
Adapter由来のものは終了理由だけを預かって転送しない形に直した。

**6値の到達可否**（`ResponseFidelityE2ETest`）:

| 値 | ストリーム経路 |
|---|---|
| `completed` / `length_limit` / `tool_call` / `content_filtered` | **届く**（Providerが応答として申告できる4値） |
| `cancelled` | 届かない。利用側の切断が原因なので受け取る相手がいない |
| `error` | 届かない。13.3「異常時は `event: error` で終端」に従い ERROR チャンクで表す |

「検証していない」と「構造上届かない」を後から区別できるよう、後者もテストとして明示した。

### 9.4 CONTENT_FILTERED（ADR-0037）

調査の結果、**例外経路はコア側で開通済み**だった。`ErrorClassificationService` が
`CONTENT_FILTERED → ErrorCode.CONTENT_FILTERED(422), retryable=false, fallbackable=設定可,
cbRecordable=false` と 2.11 の表どおりに実装している。塞がっていたのは2点で、
どちらも解消した。

1. **Adapterが分類を作れない** → `AdapterContractTest` に
   `contentFilteringSurface()` を**抽象メソッド**として追加し、Adapterに
   「例外側／応答側／再現不能（理由必須）」のいずれかを**宣言させる**。宣言に応じて検証するため、
   「再現できないからスキップ」で緑になることがなくなった
2. **Streamingで届かない** → §9.3で解消

エラー分類側は、Providerがエラーステータスを返す場合のために予約されたまま維持する
（adapter-mock は例外側で申告し、実際に検証している）。

### 9.5 max_tokens / seed（ADR-0040）

- `AdapterRequest.modelMaxOutputTokens` を追加し、Routingが確定したModelの上限がAdapterへ届くようにした。
  解決順は `params.maxTokens` → `modelMaxOutputTokens` → Adapterの既定。
  以前は「Modelを8192で登録してもAdapterの既定4096で頭打ち」という静かな食い違いがあった
- `seed` は**黙って捨てない**。対応する概念が無いため `UNSUPPORTED_CAPABILITY` で明示的に拒否し、
  Providerを呼ぶ前に落とす。利用側が指定したパラメタを無言で無視しないための判断である

### 9.6 credentialRefs（ADR-0038）と、その過程で見つかった欠落

`AdapterConfig.credentialRefs` を追加し、Adapterは `state == ACTIVE` のものを選ぶ。
adapter-mock の固定ダミー参照も是正し、実Adapterと同じ経路を通るようにした。

**`authenticate()` のシグネチャは変更しない**と判断した。理由: `execute()` も秘密値を要るが
`CredentialRef` を受け取らないため、`authenticate(ref)` にしても解決しない。
Provider単位の設定を運ぶのは `initialize(config, …)` の役目であり、そこに置くのが筋である。
シグネチャ変更はSPIのメジャー更新を要する割に得るものが無い。

> **新たに見つかった欠落（ADR-0041）**: `ProviderAdapter.initialize(config, secrets)` は
> **本番のどこからも呼ばれていない**。`PluginManager` のKDocが「Provider登録フローが別途行う」と
> 書いたまま、その登録フローが実装されていない。つまり `credentialRefs` を追加しても、
> **現状では実Adapterへ届かない**。P16では欠落の記録に留めた（配線には
> 「1つのPluginを複数のProviderが共有する場合にインスタンスをどう分けるか」の設計判断が要り、
> 本タスクの範囲を超えるため）。ADR-0041に詳細を記録した。

### 9.7 modality申告（ADR-0039）と capabilityConstraints の消費（作業2-7）

**`capabilityConstraints()` の戻り値は、本番コードのどこからも読まれていなかった。**
Adapterが実装し、テストのフェイクが実装しているだけで、コア側に読み手がゼロ。
「実装済みだが機能していない」の再発である（`MetricsEngine`・`AuditEngine`・
`CapabilityRegistry` に続く4件目）。

`ModelCapability.constraints` も `Map<String,String>` の自由領域で誰も読んでいなかった。
**SPI側とRouting側の両方が不足**していたと判定し、両方を直した。

- SPI: `CapabilityConstraints.supportedInputModalities: Set<Modality>`
- ドメイン: `ModelCapability.supportedInputModalities: Set<Modality>`
- Routing: `RoutingHardFilters.passesModalityFilter` をハードフィルタ列へ追加し、
  `RoutingRequest.requiredModalities`（`ExecutionEngine` がリクエストの `ContentPart` から導出）と突き合わせる

空集合は「未申告」であり「非対応」ではない。未申告のModelは従来どおり候補に残す
（全Modelを落とすと、modalityを宣言していない既存の登録がすべて使えなくなるため）。
`ModalityRoutingE2ETest` が、非対応Providerが**一度も呼ばれずに**候補から外れること、
対応Providerは選ばれること、未申告Modelは残ることを確認する。

なお `capabilityConstraints()` 自体の消費経路は**まだ無い**。今回Routingが読むのは
ドメイン側の `ModelCapability` であり、Adapterの申告をModel登録へ取り込む経路
（`discoverModels` の結果を承認する 15.1 Step6）は未実装のままである。
**これは残る欠落として記録する**——申告口を作っただけで読み手を作らなければ、
今回直したはずの「実装済みだが機能していない」を繰り返すことになる。

### 9.8 typealias 経由のネスト型参照

§3.7に記録したとおり、Kotlinはtypealias経由でネストした型（`ContentPart.Text`）へ
アクセスできない。SPIは `TextContentPart` 等のフラットなaliasを別途用意しており、
新規Adapter作者はそちらを使う必要がある。設計書は編集できないため、
15章相当の記述としてここと `SpiSurface` のKDocに残す（ADR-0016の制約の実務上の帰結）。

### 9.9 不変条件9: 違反注入による確認

新規・変更した検査に、意図的な違反を一時的に注入して**実際に落ちること**を確認した
（確認後はいずれも復旧済み）。

| 注入 | 落ちた検査とメッセージ |
|---|---|
| Adapterが `MESSAGE_END` に `finishReason` を載せないようにする | `AdapterContractTest > the terminal stream chunk carries a finish reason`「MESSAGE_ENDに終了理由が載っていません。載せないと length_limit と正常完了が区別できません」 |
| Routingの modality ハードフィルタを外す | `ModalityRoutingE2ETest > a provider that does not accept images is excluded before it is ever called`「Expected ApapException to be thrown, but nothing was thrown」（＝非対応Providerが呼ばれてしまう） |
| Streaming の `finishReason` 伝播を切る（公開APIへの写し漏れ） | `ResponseFidelityE2ETest` の5項目「Adapterが申告した終了理由がストリーム経路で失われています。届いた: null」 |
| `outputSchema` を再び黙殺する | `StructuredOutputTest > the schema reaches the provider through the native structured output mechanism`「output_config.format が送られていません」 |
| findings文書から `[要実測]` の印を消す | `UnverifiedAgainstLiveApiTest > while unverified, the findings document must say so`「[要実測]の印が findings 文書から消えています。この印が『一通り検証した』という記憶への置き換わりを防ぐ唯一の手段です」 |

最後の1件は、**文書の記述そのものを機械検証の対象にした**もの。作業0-2の
「時間が経つと記憶に置き換わる」への対策が、文書を書いただけで終わらないようにしている。
