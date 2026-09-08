# ADR-0041: `ProviderAdapter.initialize()` が本番のどこからも呼ばれていない

- **ステータス**: Accepted（P16で検出、**P17で実装**）
- **関連要件**: FR-PRV-001〜006, FR-SEC-001, FR-SEC-002, NFR-EXT-001
- **関連する設計書**: 03_基本設計.md 3.3.2, 15_Provider追加手順.md 15.1 Step4-8
- **検出**: P16 ADR-0038（credentialRefs追加）の実装中

## コンテキスト

`AdapterConfig` に `credentialRefs` を足す作業の途中で、より根本的な欠落が見つかった。

**`ProviderAdapter.initialize(config, secrets)` は本番コードのどこからも呼ばれていない。**

- `AdapterConfig(...)` を構築する本番コードが**存在しない**（型定義とテストのみ）
- `PluginManager` のKDocは
  「Provider固有設定を伴う`initialize(config, secrets)`の実際の呼出は、
  Provider登録フロー（15.1 Step4以降、**本タスクの範囲外**）が別途行う」と明記しているが、
  その登録フローは実装されていない
- `PluginManagerAdapterRegistry.resolve()` は `PluginManager.getAdapter()` の戻り値を
  そのまま返すだけで、初期化しない
- `ProviderManager.completeValidation()` は**未初期化のAdapter**に対して
  `validateCredential()` / `healthCheck()` を呼ぶ

`adapter-mock` は `initialize` 未呼出を検知して例外にするため、テストでは
フィクスチャが明示的に初期化しており、この欠落が表面化していなかった。

## 影響

- **ADR-0038で `credentialRefs` を追加しても、実Adapterには届かない。**
  届ける経路（`initialize`）が呼ばれないため
- 同様に `endpoints` / `rateLimits` / `regions` / `options` も届かない。
  実Adapterはエンドポイントすら知らない状態で `execute` されることになる
- 実Providerを本番配線で動かすと、最初の呼出で初期化エラーになる
  （`adapter-mock`と同じ実装規約に従う限り、静かに壊れるのではなく失敗する。
  そこは救い）

## 決定（案。次フェーズで確定させる）

初期化の呼び出し主体と、**1つのPluginを複数のProviderが共有する場合の扱い**を決める必要がある。
後者が本質的な論点で、これを決めずに配線だけ足すと誤った設定で動く危険がある。

- **案A（Provider単位でAdapterインスタンスを分ける）**: `PluginManager` が
  Provider登録ごとに別インスタンスを生成する。設定の混線が原理的に起きない。
  ClassLoaderは共有できるためコストは小さいが、`AdapterRegistry.resolve(pluginId)` の
  シグネチャを `resolve(provider)` 相当へ変える必要がある
- **案B（共有インスタンス + 初期化の一意性チェック）**: 現状どおり1 Plugin = 1インスタンスとし、
  `ProviderManager` が検証・有効化のタイミングで初期化する。同じPluginを別Providerが
  異なる設定で初期化しようとしたら**失敗させる**（黙って上書きしない）

案Aが正しいが影響範囲が広い。案Bは小さいが「1 Plugin = 1 Provider」の制約を課す。

## 決定（P17で確定）

**案Aを採る。Plugin（ロード済みクラス・ClassLoader）は共有し、Adapterインスタンスは
Providerごとに1つ持つ。**

案Bを採らない理由は、これが配線の穴であると同時に**セキュリティ要件の穴**だからである。
`AdapterRegistry` が `pluginId` をキーにしている限り、1つのインスタンスが複数Providerで
共有される。Providerは `endpoints` / `credentialRefs` / `rateLimits` / `regions` を
**Providerごとに**持つため、共有インスタンスは自分がどのProviderとして動いているのかを
決められない。この状態では **FR-SEC-005（Provider Isolation）と
NFR-SEC-004（Adapterは自ProviderのCredentialのみアクセス可能）が原理的に成立しない**——
実装の巧拙ではなく、構造として成立しない。案Bの「初期化の一意性チェック」は
混線を検知して失敗させるだけで、分離そのものは与えない。

### 具体

| 決めたこと | 実装 |
|---|---|
| インスタンスの粒度 | Providerごとに1つ（Plugin・ClassLoaderは共有のまま） |
| 生成の主体 | `PluginManager.newAdapter(pluginId)` が生成器を提供し、`ProviderAdapterProvisioner` がProviderごとに保持する |
| 初期化の主体 | `ProviderManager` が状態遷移の中で `provision(provider)` を呼び、`initialize(config, secrets)` が実行される |
| `AdapterRegistry` のキー | `pluginId` → **`providerId`**（`resolve(providerId)`） |
| Credentialのスコープ | 各インスタンスへ渡す `SecretAccessor` は、そのProviderの `credentialRefs` に**スコープされる**。他Providerの参照を渡すと `CredentialAccessDeniedException` |

### ライフサイクル

| 契機 | 動作 |
|---|---|
| VALIDATING | 生成し `initialize(config, secrets)`。直後の `completeValidation` が `validateCredential` / `healthCheck` を呼ぶため、この時点で初期化済みである必要がある |
| SUSPENDED→ACTIVE（復帰） | VALIDATINGを通らない経路のため `enable` でも用意を保証する |
| 設定変更・Credentialローテーション（9.7） | 設定の指紋（endpoints / rateLimits / regions / credentialRefs（**状態を含む**））が変われば作り直す。**プロセス再起動を要求しない** |
| DISABLED / DELETED | `shutdown()` して破棄（冪等） |

### 有効化の過程でインスタンスは2回作られる

REGISTERED→VALIDATING時点のCredentialはSTANDBYで、検証に合格して初めてACTIVEへ昇格する。
Adapterは「ACTIVEのCredentialを使う」規約（`AdapterConfig.credentialRefs`のKDoc）なので、
昇格は**Adapterへ届かなければ意味がない**。したがって`enable`での`provision`が指紋の変化を
検知して作り直す——検証用（STANDBY）と稼働用（ACTIVE）で2つ作られ、前者は`shutdown()`される。

有効化1回につき1回の追加生成であり、リクエスト毎ではない。これを避けようとして
Credentialの状態を指紋から外すと、昇格もローテーションもAdapterに反映されなくなる
（`docs/adapter-spi-findings.md` §10.3の注入実験がそれを示している）。

### 検証

`ProviderAdapterProvisionerTest` / `ProviderAdapterLifecycleTest` / `AdapterLifecycleWiringTest`:
同一Pluginを参照する2つのProviderが別インスタンスを得ること、各インスタンスが自Providerの
Credentialのみ解決でき他Providerの `credentialRef` では失敗すること、ACTIVEに達するまでに
`initialize` が呼ばれていること、DISABLED / DELETED で `shutdown` が呼ばれること、
ローテーション後に新しいCredentialで動くこと。`AdapterLifecycleWiringTest`は同じことを
**本番の入口（`ApapEngineBuilder`）経由**で確認する（「型は揃っているのに誰も呼ばない」を
単体テストでは検出できないため）。不変条件9の違反注入は
`docs/adapter-spi-findings.md` §10.3 に記録した。

### 残る制約

`ProviderAdapterProvisioner` はインメモリのインスタンス表であり、**プロセスローカル**である。
複数Podで同じProviderを扱う構成では、各Podが自分のインスタンスを持つ。ローテーション時の
差し替えは各Podが自分の `provision` 呼出で行うため、Pod間の同期は
（Providerの状態がRepository経由で共有されている限り）不要だが、**他Podがまだ旧Credentialで
動いている時間帯は存在する**。9.7が旧Credentialを即REVOKEDにせず
REVOKED_PENDING（既定24h猶予）を挟むのは、まさにこの時間帯のためである。
