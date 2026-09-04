# ADR-0037: コンテンツ拒否を例外側と応答側のどちらで表現するか

- **ステータス**: Proposed（P15で検出。**実装は次フェーズ**）
- **関連要件**: FR-CAP-003, FR-EXE-002
- **関連する設計書**: 02_システム仕様.md 2.11（Retry/Fallback判断）, 03_基本設計.md 3.3.2
- **検出**: P15 実Provider向けAdapter第1号の実装（docs/adapter-spi-findings.md §3.1）

## コンテキスト

SPIは同じ「コンテンツ拒否」を2箇所で表現できる。

- `AdapterErrorCategory.CONTENT_FILTERED`（例外側、8分類の1つ）
- `FinishReason.CONTENT_FILTERED`（応答側）

両者の関係は設計書に定義がない。実Providerを1つ実装したところ、そのProviderは
拒否を**HTTP 200の正常応答**（`stop_reason: "refusal"`）として返し、エラーとしては返さなかった。
結果、`AdapterContractTest` の `category=CONTENT_FILTERED` は**構造的にパスできない**
（例外を投げる経路が存在しない）。

エラーとして返すProviderも存在しうるため、**Adapterごとにどちらで来るかが変わる**。
コア側は2.11に従ってRetry/Fallbackを判断するが、拒否が応答として来た場合は
Retry判断の土俵にすら乗らない（正常応答として利用側へ返る）。

## 決定（案。次フェーズで確定させる）

次のいずれかを採る。現時点では**採用案を確定しない**——1つのProviderの都合で
SPIを決めると2つ目で歪むため、2つ目のAdapterを別Providerで書いてから確定する。

- **案A（応答側へ一本化）**: 拒否は常に `FinishReason.CONTENT_FILTERED` の正常応答として返す。
  例外側の `CONTENT_FILTERED` は非推奨化する。Adapterはエラー応答で来た場合も応答へ変換する
- **案B（両方を許し、宣言させる）**: `AdapterContractTest` に「どちらで表現するか」を
  申告するフックを設け、申告に応じて検証項目を切り替える
- **案C（例外側へ一本化）**: 応答で来た拒否をAdapterが例外へ変換する。
  ただし部分的に生成されたテキストが失われるため、情報を捨てる方向になる

## 影響

- コア側（`ResponseMapper` / `FallbackEngine`）が「拒否はFallbackしても無駄」を
  判断できる必要がある。現状は応答として返るためFallbackの検討対象にならない
- 決めるまで、`AdapterContractTest` の該当項目はスキップのまま残る
  （スキップの理由は本ADRと `docs/adapter-spi-findings.md` に記録済み）
