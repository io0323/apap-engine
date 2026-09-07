# ADR-0041: `ProviderAdapter.initialize()` が本番のどこからも呼ばれていない

- **ステータス**: Proposed（P16で検出。**実装は次フェーズ**）
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

## 影響（この決定を保留することの）

P16では**記録に留めた**。理由は、配線を足すこと自体は小さくても、上記の共有セマンティクスを
決めずに入れると「誤った設定で動く」という、現状（動かない）より悪い状態を作りうるため。

決めるまでは、実Providerを本番配線で動かすことはできない。
`docs/adapter-spi-findings.md` の「実測が必須になる条件」（prompt-engine接続前 /
実トラフィック前）に到達したら、本ADRの解決も同時に必要になる。
