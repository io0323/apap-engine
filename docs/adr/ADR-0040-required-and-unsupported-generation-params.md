# ADR-0040: Provider必須パラメタと未対応パラメタをSPIが表現できない

- **ステータス**: Proposed（P15で検出。**実装は次フェーズ**）
- **関連要件**: FR-CAP-001, FR-EXE-002
- **関連する設計書**: 03_基本設計.md 3.3.1（GenerationParams）, 3.3.2（AdapterRequest）
- **検出**: P15 実Provider向けAdapter第1号の実装（docs/adapter-spi-findings.md §3.5）

## コンテキスト

2つの食い違いが出た。

### 1. Provider側で必須のパラメタが、SPIでは任意

実APIは `max_tokens` が**必須**だが、`GenerationParams.maxTokens` は `Int?`。
未指定時にAdapterが既定値を捏造するしかない。

本来はModelの `maxOutputTokens`（Model登録時に設定済み）を使うべきだが、
**その値はAdapterへ渡らない**——`AdapterRequest` は `modelName: String` しか持たない。
結果、Modelを 8192 で登録していてもリクエストが `maxTokens` を省略すると
Adapterの既定値（4096）で頭打ちになり、**設定と実挙動が静かに食い違う**。

### 2. Provider側に無いパラメタが、SPIにはある

`GenerationParams.seed` に対応するパラメタが実APIに無い。現状Adapterは黙って捨てている。
再現性を期待した利用側は、それが効いていないことに気付けない。

## 決定（案。次フェーズで確定させる）

- **1について（案A）**: `AdapterRequest` にModel側の上限を含める
  （`modelMaxOutputTokens: Int?` 等）。Adapterは `params.maxTokens ?: modelMaxOutputTokens ?: 既定値` と解決できる
- **1について（案B）**: コア側（`RequestMapper`）が `Model.maxOutputTokens` で
  `maxTokens` を埋めてからAdapterへ渡す。SPIは変えずに済むが、
  「Providerが必須とするか」をコアが知らないまま常に埋めることになる
- **2について**: Adapterが「解釈しなかったパラメタ」を申告できるようにする。
  応答の `AdapterResponse.metadata` に載せる案が最も影響が小さい

## 影響

- 案Bはコアだけで閉じるためSPI変更が要らない。一方、Provider側の必須／任意を
  コアが判断することになり、Vendor Neutralityの観点で筋が悪い
- 決めるまで、`maxTokens` 省略時の実効上限はAdapterの既定値に依存する
  （`adapters/adapter-anthropic` では 4096。KDocに明記済み）
