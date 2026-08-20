# ADR-0010: ProviderAdapter SPIへのトークン推定オプショナルメソッド追加

## ステータス

Accepted（2026-08-20）。SPI変更を伴うため独立ADRとして起票。

## コンテキスト

[ADR-0009](ADR-0009-tokenizer-estimation-strategy.md) の条件4に基づき、Adapterが Provider固有の正確なトークナイザーを提供できる場合にそれを優先利用できるようにする必要がある。これは `docs/design/03_基本設計.md` 3.3.2（`ProviderAdapter` SPI、apap-adapter-spiにおける最重要契約）へのインターフェース変更にあたるため、[ADR-0009](ADR-0009-tokenizer-estimation-strategy.md) 本体とは別に独立したADRとして扱う。

## 決定

`ProviderAdapter` SPIに**オプショナルメソッド** `estimateTokens(input): TokenCount?` を追加する。

- Adapterが実装を提供しない場合はnullを返し、コア側は `HEURISTIC` モードにフォールバックする（[ADR-0009](ADR-0009-tokenizer-estimation-strategy.md)）。
- Adapterが正確なトークナイザーを提供できる場合はそれを優先し、`EXACT` モードとして扱う。

## 影響（Consequences）

- **制約**: 既存Adapter実装への破壊的変更にはならない（オプショナルメソッド、デフォルト実装はnull返却）。ただしSPIバージョン（`spiVersion`、3.3.2）のマイナーバージョンアップとして扱う。
- `docs/design/03_基本設計.md` 3.3.2 は編集しない。設計書との差分（メソッド追加）は本ADRを実装コード上・Adapter開発者向けドキュメント（`docs/design/15_Provider追加手順.md` 参照時）の参照根拠とする。
- **見直す条件**: 将来的に `estimateTokens` を必須化する場合は、SPIメジャーバージョンアップとして別途ADRを起票する。
- **関連**: `docs/design-review.md` #9 / U5 / A2、[ADR-0009](ADR-0009-tokenizer-estimation-strategy.md)。
