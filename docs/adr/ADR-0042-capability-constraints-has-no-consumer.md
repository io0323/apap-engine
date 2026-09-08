# ADR-0042: `ProviderAdapter.capabilityConstraints()` を削除する（消費者が現れない申告口）

- **ステータス**: Accepted（P18で決定・実装）
- **関連要件**: FR-RTE-002, FR-CAP-001, NFR-EXT-001, NFR-EXT-002
- **関連する設計書**: 03_基本設計.md 3.3.2, 04_ドメイン設計.md 4.3.2, 06_クラス図.md, 15_Provider追加手順.md 15.1
- **関連ADR**: ADR-0016（SPI semver）, ADR-0039（modality申告）, ADR-0040

## コンテキスト

`ProviderAdapter.capabilityConstraints(capabilityId): CapabilityConstraints` は
**本番コードのどこからも呼ばれていない**。P16（ADR-0039の実装時）とP17で検出しながら、
2フェーズ続けて「残る欠落」として記録するだけで持ち越されていた。

消費者のいないSPIメソッドは、単に無駄なのではない。Adapter作者に「実装すれば効く」と
誤認させる。実際`adapter-anthropic`は`maxInputTokens`・`streamable`・`supportsTools`・
`extra`まで丁寧に埋めており、その値はどこにも届いていない。

### 事実の確認

本番ソース（`modules/*/src/main`・`gateway/*/src/main`）に呼び出しは0件。実装しているのは
2つのAdapterと2つのテストダブルのみ。Contract Testにも項目が無い。

各フィールドについて、**同じ意味を持ち、実際に消費されているドメイン側の対応物**が存在する。

| CapabilityConstraints | 実際に消費されている対応物 | 消費者 |
|---|---|---|
| `maxInputTokens` | `Model.contextWindow` | `ContextAssemblyService.computeBudget`（`DefaultContextManager`） |
| `maxOutputTokens` | `Model.maxOutputTokens` | 同上、および`AdapterRequest.modelMaxOutputTokens`（ADR-0040） |
| `streamable` | `CapabilityDefinition.streamable`（2.4のStreaming列） | Capability Registry / 探索API |
| `supportsTools` | Capability `tool_calling` / `function_calling` の有無 | Routing候補抽出（2.5.2 step1） |
| `supportedInputModalities` | `ModelCapability.supportedInputModalities` | `RoutingHardFilters.passesModalityFilter`（ADR-0039） |
| `extra` | — | 自由文字列。KDoc自身が「機械的な判断には使わない」と明記 |

### 粒度が合っていない

より本質的な問題として、このメソッドの粒度は**(Provider, Capability)**だが、
Routingも実行前検証も判断するのは**(Model, Capability)**である。同じProviderでも
Modelごとにcontext windowもmodality対応も異なる。`adapter-anthropic`の実装が
Provider共通の既定値（`DEFAULT_CONTEXT_WINDOW`）を返しているのはその現れで、
**もしコアがこれを読んだら、登録済みModelの実値と食い違う第二の真実になる**。

ADR-0039が「modality申告をSPIに足す」だけで終わらず、ドメイン側の`ModelCapability`に
同名フィールドを足してそちらをRoutingに読ませたのは、この粒度の差が理由である。
**必要だった1フィールドは、既に正しい置き場へ移してある。**

## 選択肢

- **案A（配線する）**: 残りの制約（最大入力サイズ、並列tool数など）に消費者を作る
- **案B（削除する）**: SPIから削除する。メソッド削除はメジャー更新
- **案C（保留を明示する）**: 消費者がいないことを機械検査で可視化して残す

## 決定

**案Bを採る。`capabilityConstraints()`と`CapabilityConstraints`型、および用途が無くなる
`Modality`のtypealiasをSPIから削除し、`apap-adapter-spi`を 1.1.0 → 2.0.0 とする。**

### 案Aを採らない理由

配線先の候補は4.3.2が挙げる「最大入力サイズ、並列tool数等」だが、

- **最大入力サイズ**は`Model.contextWindow`として既に消費されている。Adapter側の値は
  Provider共通でしかなく、読めば真実が二重になる（上記）
- **並列tool数**は、これを要求する要件（FR/NFR）も、実行前に検証している経路も、
  ハードフィルタの条件（2.5.2 a〜g）も存在しない。消費者を作るには「どう使うか」を
  こちらで決める必要があり、それは「未確定事項を勝手に決めない」に反する

### 案Cを採らない理由

案Cは**形が誤っていると分かっている申告口**を保存する。将来必要になるのは
(Provider, Capability)の制約ではなく(Model, Capability)の制約であり、
Adapterから申告させるなら置き場は`DiscoveredModel`（Modelごと、15.1 Step6の自動登録で
承認される単位）である。誤った形を残したまま「いつか使う」と書いても、次に読む人は
また同じ判断をやり直すことになる。3フェーズ目の持ち越しにしない。

### メジャー更新の判断

メソッドの削除は既存Adapterのコンパイルを壊すため、ADR-0016の規約によりメジャー更新に当たる
（`SpiSurface.version` = 2.0.0）。**今が最も安いタイミング**である——SPIを実装しているのは
リポジトリ内の2つ（`adapter-anthropic` / `adapter-mock`）だけで、外部配布はまだ無い。
P16でSPI拡張をまとめた判断と同じ理由による。

Adapter側の対応は`override`を1つ削除し、`plugin.yaml`の`spi_version`を`>=2.0 <3.0`にすること。

あわせて、`ApapEngineBuilder`のホスト側SPIバージョンが`SemVer(1, 0, 0)`直書きのままだった
（`SpiSurface`が無かった頃の名残）のを`SpiSurface.version`へ single-source した。
放置すると、レンジを正しく書いたPluginほど互換性判定で弾かれる。

## 設計書との差分（不変条件8）

`docs/design/`は編集しない。実装は次の点で設計書と異なる。

| 設計書 | 実装（正） |
|---|---|
| 03_基本設計.md 3.3.2: `capabilityConstraints(capabilityId): CapabilityConstraints` | 削除。能力申告は`supportedCapabilities()`のみ |
| 06_クラス図.md: `ModelCapability.constraints: CapabilityConstraints` | `constraints: Map<String, String>`（人間向けの覚書。機械判断には使わない）＋ `supportedInputModalities: Set<Modality>`（ADR-0039、Routingが読む） |
| 15_Provider追加手順.md 15.1 Step1: 実装必須メソッドに`capabilityConstraints`を含む | 含まない |

## 再発防止（不変条件9）

削除して終わりにすると、次に誰かがSPIへメソッドを足したとき同じことが起きる。
`SpiSurface.adapterMethodConsumers`に**メソッド → 本番の消費者**のクローズドセットを置き、
`ProviderAdapterSurfaceTest`が次の3点を機械検証する。

1. 表と`ProviderAdapter`の宣言が過不足なく一致すること（メソッド追加時に表の更新を強制する）
2. 消費者を宣言した項目には、本番ソースに実際の呼び出しがあること
   （**テストからの呼び出しは数えない**——`capabilityConstraints`はフェイクだけが実装していた）
3. 「消費者なし」と宣言した項目には本当に呼び出しが無く、かつ理由が書かれていること

この検査により、**現時点で本番の消費者を持たないSPIメソッドが他に5つある**ことが可視化された。
いずれも削除ではなく理由付きで残す判断とし、表に記録した。

| メソッド | 消費者が無い理由 |
|---|---|
| `spiVersion` | ホストは`plugin.yaml`の`spi_version`レンジで互換性を判定しており、Adapter自身の申告は突き合わせていない（ADR-0016の残課題） |
| `translateTools` | コアは`AdapterRequest.tools`をSPI共通形式のまま渡し、Provider形式への変換はAdapter内部で完結している |
| `discoverModels` | 検出結果を承認してModel登録する経路（15.1 Step6）が未実装 |
| `fetchUsage` / `fetchCost` | Provider側集計APIを取り込むユースケースが未実装（findings §4.2） |

`capabilityConstraints`との違いは、**いずれも消費者の置き場と形が定まっている**ことである
（Step6・課金集計・互換性判定）。形が誤っていた`capabilityConstraints`とは扱いを分ける。

## 影響

- 実Adapterの制約は、Model登録時に`ModelCapability`へ設定する運用になる。
  `adapter-anthropic`が申告していたmodality（TEXT/IMAGE/JSON）は同AdapterのREADMEへ移し、
  登録時に何を設定すべきかを明示した
- 15.1 Step6（`discoverModels`の結果を承認してModel登録）を実装する際、Adapterから
  modalityを申告させるなら`DiscoveredModel`へModelごとのフィールドとして足すこと。
  そのとき`Modality`のtypealiasをSPIへ戻す（追加なのでマイナー更新）
