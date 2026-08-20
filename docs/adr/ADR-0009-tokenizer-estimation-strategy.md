# ADR-0009: Tokenizer推定方式（EXACT/HEURISTICモードと用途別安全マージン）

## ステータス

Accepted（2026-08-20）

## コンテキスト

`docs/design/02_システム仕様.md` 2.9 および `docs/design/04_ドメイン設計.md` 4.6 の `TokenEstimationService` は「Providerがusageを返さない場合はTokenizer推定」とのみ記載し、具体的な推定方式・ライブラリが未定義だった（`docs/design-review.md` #9, U5, A2 参照）。

トークン推定は次の3用途で使われるが、誤差の影響が非対称である。

| 用途 | 過小推定した場合 | 過大推定した場合 |
|---|---|---|
| Quota事前予約（2.8 step 7） | 上限を超過して通してしまう（後でcommit時に補正可能） | 不要な拒否 |
| Routingのコストスコア（2.5.2） | 相対順位のみ影響、致命的でない | 同左 |
| **Context Window適合（2.16）** | **Provider呼出が確実に失敗し、レイテンシとコストを捨てる実障害になる** | 履歴を過剰に切り詰め品質劣化 |

3番目が特に危険。設計の安全マージン5%（2.16）は「正確なトークナイザーがある前提」の値であり、文字数ベースの粗い近似では日本語・コード・多言語混在で簡単に超過しうる。

## 決定

簡易近似（文字数ベース等）で開始することは承認するが、以下を条件とする。

1. `TokenEstimationService` の推定モードを **`EXACT` / `HEURISTIC`** の2値で持つ。
2. **安全マージンをモード連動にする**: `EXACT` → 5%（設計通り）、`HEURISTIC` → 15%（設定可）。
3. ヒューリスティックは**モデル別の文字/トークン比を設定可能**にし、常に切り上げる。
4. `usage.estimated=true` の付与は設計通り維持する。

（`ProviderAdapter` SPIへの `estimateTokens` メソッド追加は [ADR-0010](ADR-0010-adapter-spi-estimate-tokens-method.md) で独立して扱う。）

## 影響（Consequences）

- **制約**: `ContextAssemblyService`（`docs/design/04_ドメイン設計.md` 4.6）は現在の推定モードを参照し、安全マージンを動的に切り替える実装とする。ハードコードで5%固定にしてはならない。
- **見直す条件**: コスト精度・Context超過の実障害率が問題になった時点で、モデルファミリー別の専用トークナイザー導入を検討する（[ADR-0010](ADR-0010-adapter-spi-estimate-tokens-method.md) のSPI拡張で対応可能）。
- **関連**: `docs/design-review.md` #9 / U5 / A2、[ADR-0010](ADR-0010-adapter-spi-estimate-tokens-method.md)。
