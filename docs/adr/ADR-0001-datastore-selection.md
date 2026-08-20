# ADR-0001: データストア技術選定とCB/RateLimit状態ストアの分離

## ステータス

Accepted（2026-08-20）

## コンテキスト

`docs/design/12_ER図.md` はリレーショナルモデル（PK/FK/UQ）でエンティティを定義しているが、具体的なRDBMS製品は指定していない。また `docs/design/02_システム仕様.md` 2.14（Cache仕様）・2.13（Circuit Breaker仕様）・3.15（DI構成）は「分散KVS」「共有ストア」という抽象名のみでCache Store / Circuit Breaker共有ストア / Rate Limiter共有カウンタストアの具体技術を定義していない（`docs/design-review.md` #1, U12, U14, A3 参照）。

一方、`docs/design/02_システム仕様.md` 2.8（Request Flow）は非Streaming実行の各リクエストで Circuit Breaker 通過確認（8a）・Rate Limiter取得（8b）を必須ステップとしており、`docs/design/01_要件定義.md` NFR-PRF-001 はAPAP付加レイテンシを p50 ≤ 15ms / p99 ≤ 50ms と定めている。この経路をRDBMSへのラウンドトリップにすると、それだけでレイテンシ予算を使い切る。

## 決定

- 永続データ（Provider / Model / Alias / Policy / Conversation / Turn / Usage / Audit / EventStore / Memory）は単一RDBMS + ベクトル拡張で統一する。
- **Circuit Breaker状態・Rate Limiterのトークンバケット・Response CacheはRDBMSに置かない。** `CacheStore` / `CircuitBreakerStateStore` / `RateLimitCounterStore` をPortとして分離し、以下2系統の実装を用意する。
  - **In-Memory実装（既定）**: 単一プロセス・埋込利用（`apap-runtime` を prompt-engine が依存する構成）ではこれで十分。外部依存ゼロで動作する。
  - **分散KVS実装**: `apap-gateway` のマルチノード運用時に切替える。実装時期は将来フェーズ（P8想定）。
- Vector Store（`MemoryRepository` の実装）は当面RDBMS拡張で実装する。`docs/design/01_要件定義.md` FR-CTX-004 は優先度Sであり初期の性能要求が低いため。Port経由のため後日専用Vector DBへ差替可能。

具体的なRDBMS製品・分散KVS製品は本ADRでは選定しない。デプロイ環境の決定に委ねる。

## 影響（Consequences）

- **制約**: CB/RateLimitの実装を将来RDBMSへ統合する変更は行ってはならない（NFR-PRF-001を壊すため）。この判断を知らない実装者が「一貫性のためRDBに寄せよう」としないよう、該当コード（`apap-execution` の Circuit Breaker実装、`apap-cache` のRate Limiterカウンタ実装）にこのADRへの参照コメントを残すこと。
- **見直す条件**: 単一プロセス embedded 利用のみで完結する用途（prompt-engine 組込のみ）が確定し、マルチノード運用が不要と判明した場合、分散KVS実装は実装しなくてよい（In-Memoryのみで運用継続）。
- **関連**: `docs/design-review.md` #1 / U12 / U14 / A3。
