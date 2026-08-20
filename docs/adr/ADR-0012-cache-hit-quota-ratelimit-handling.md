# ADR-0012: キャッシュ短絡時のQuota/RateLimit扱い

## ステータス

Accepted（2026-08-20）

## コンテキスト

`docs/design/02_システム仕様.md` 2.14は「キャッシュ応答にはUsage/Costは記録するがProvider呼出コストは0」とするが、`docs/design/03_基本設計.md` 3.3.3 の `DefaultExecutionEngine.execute()` 疑似コードではCache Engineの参照（`cacheEngine.lookup`）がRouting/Quota処理より前に行われており、キャッシュヒット時にQuota予約・Rate Limiter消費が発生するかが明記されていなかった（`docs/design-review.md` 補足, A11 参照）。

## 決定

- **requestsカウント**: 消費する（APAPのリソースを使っており、濫用防止のため）。
- **tokens / cost**: 0として計上する（Provider呼出が発生していないため）。
- **Rate Limiter**: テナントスコープは適用し、Providerスコープは適用しない。

## 影響（Consequences）

- **制約**: `DefaultExecutionEngine.execute()`（3.3.3）のキャッシュ短絡パスでも、テナントQuotaのrequestsカウンタとテナントRate Limiterの消費は必ず通すこと。Provider側のRate Limiter・Quotaトークン消費は通さない。
- **見直す条件**: キャッシュヒット率が高いテナントでrequestsカウントが不当に厳しいと判明した場合、Quota上のrequests計上ルールを見直す。
- **関連**: `docs/design-review.md` 補足 / A11、[ADR-0001](ADR-0001-datastore-selection.md)（Cache/RateLimitストア分離）。
