# ADR一覧

Architecture Decision Record。`docs/design/*.md` は一次情報として編集しない方針（`01_CLAUDE.md` 不変条件8）のもと、設計書の曖昧点・矛盾・未定義要素に対する実装判断をここに記録する。1論点1ファイル、番号は連番。

| # | 題名 | ステータス | 関連する design-review.md 項目 |
|---|---|---|---|
| [ADR-0001](ADR-0001-datastore-selection.md) | データストア技術選定とCB/RateLimit状態ストアの分離 | Accepted | #1, U12, U14, A3 |
| [ADR-0002](ADR-0002-secret-store-responsibility-boundary.md) | Secret Store責務分界（外部Store採用、Rotation状態機械はAPAP自身の責務） | Accepted | #2, U13 |
| [ADR-0003](ADR-0003-initial-transport-protocol-scope.md) | 初期対応トランスポートプロトコルのスコープ（REST/SSEのみ） | Accepted | #3, U8, U9, U10 |
| [ADR-0004](ADR-0004-ciap-claim-mapping-config-driven.md) | CIAP JWTクレームマッピングの設定駆動化 | Accepted（実値はCIAP側と合意待ち） | #4, U15 |
| [ADR-0005](ADR-0005-pii-masking-early-implementation.md) | PIIマスキングの先行実装方針（既定OFFを前提としたopt-inゲート） | Accepted | #5, U6, A8 |
| [ADR-0006](ADR-0006-region-code-table-config-driven.md) | Region内部コード表の設定駆動化 | Accepted | #6, U7, A6 |
| [ADR-0007](ADR-0007-batch-retention-terminal-at.md) | Batch結果保持期間の起算点（terminal_at）とデータ種別ごとの保持ポリシー整理 | Accepted | #7, A15 |
| [ADR-0008](ADR-0008-credential-ref-four-state-model.md) | CredentialRef状態モデルを4状態（09章）で正とする | Accepted | #8, C1 |
| [ADR-0009](ADR-0009-tokenizer-estimation-strategy.md) | Tokenizer推定方式（EXACT/HEURISTICモードと用途別安全マージン） | Accepted | #9, U5, A2 |
| [ADR-0010](ADR-0010-adapter-spi-estimate-tokens-method.md) | ProviderAdapter SPIへのトークン推定オプショナルメソッド追加 | Accepted | #9, U5, A2 |
| [ADR-0011](ADR-0011-structured-output-correction-retry-budget.md) | Structured Output是正リトライと通常Retry予算の統合 | Accepted | #10, A10 |
| [ADR-0012](ADR-0012-cache-hit-quota-ratelimit-handling.md) | キャッシュ短絡時のQuota/RateLimit扱い | Accepted | 補足, A11 |
| [ADR-0013](ADR-0013-audit-digest-tenant-salt.md) | Audit request_digestへのテナント固有ソルト付与 | Accepted | 補足 |
| [ADR-0014](ADR-0014-event-store-snapshot-persistence.md) | EventStoreRepositoryはスナップショット永続化とバージョン照会の両方を提供する | Accepted | 補足（NFR-DAT-003） |
| [ADR-0015](ADR-0015-adapter-test-source-may-depend-on-apap-testkit.md) | adapters/*のtestソースセットはapap-testkitへの依存を許可する（mainのみ厳格に制限） | Accepted | 補足（15章 Step3、16.1） |
| [ADR-0016](ADR-0016-adapter-spi-typealias-boundary-and-spi-surface.md) | Adapter SPIのtypealias境界はソースレベル分離のみを提供する（SPI公開面の明示管理とバージョニング規約） | Accepted | 補足（15.1、16.1、NFR-MNT-001） |
| [ADR-0017](ADR-0017-jackson-version-alignment-and-single-json-stack.md) | apap-runtime埋込時のJacksonバージョン整合と、プロジェクト全体でのJSONスタック一本化 | Accepted | 補足（NFR-MNT-001、埋込ライブラリとしてのapap-runtime） |
| [ADR-0018](ADR-0018-fallback-budget-threshold-uses-p90-latency.md) | Fallback移行の予算充足判定にp90レイテンシを使う（設計書2.12からの意図的逸脱） | Superseded by ADR-0020 | 補足（P6着手前レビュー、NFR-AVL-002） |
| [ADR-0019](ADR-0019-adapter-chunk-explicit-tool-call-completion-signal.md) | AdapterChunkにToolCallデルタの明示的完了シグナルを追加する | Accepted | 補足（P6着手前レビュー、02_システム仕様.md 2.10） |
| [ADR-0020](ADR-0020-fallback-budget-threshold-reverts-to-p50-latency.md) | Fallback移行の予算充足判定をp50レイテンシへ復帰する（ADR-0018のSupersede） | Accepted | 補足（着手前レビュー、NFR-AVL-002） |
| [ADR-0021](ADR-0021-unpriced-model-hard-exclusion.md) | 単価未登録Modelはルーティング候補から除外する（ハードフィルタ方式、P7着手前レビュー） | Accepted | 補足（P7着手前レビュー、FR-OBS-005、FR-RTE-002） |
| [ADR-0022](ADR-0022-query-embedder-resilience-boundary.md) | QueryEmbedderの実装位置とResilience機構からの意図的な分離（実ベクトル化はP8以降） | Superseded by ADR-0023 | 補足（P7着手前レビュー、FR-CTX-004） |
| [ADR-0023](ADR-0023-query-embedder-shares-execution-resilience.md) | QueryEmbedderはメインリクエストと同じCircuit Breaker/Rate Limiterを経由する（ADR-0022のSupersede） | Accepted | 補足（P8着手前レビュー、FR-CTX-004） |
| [ADR-0024](ADR-0024-build-logic-toolchain-instability-root-cause.md) | `build-logic`のビルド不安定性の根本原因（IDEのバックグラウンドビルド競合・JDK21欠如・Gradle 9.4.1のkotlin-dsl自己矛盾）とツールチェーン対処 | Accepted | 補足（P8着手前トラブルシューティング） |
| [ADR-0025](ADR-0025-p8-infrastructure-technology-selection.md) | P8 apap-infrastructure/apap-plugin実装の技術選定（PostgreSQL+Flyway+素のJDBC、Redis+Lettuce、Plugin署名検証はjava.security.Signature自前実装） | Accepted | 補足（P8着手前レビュー、ADR-0001/ADR-0002の実装方式選定） |

## 命名規則

`ADR-XXXX-kebab-case-title.md`。番号は4桁ゼロ埋め連番、欠番・再利用はしない（撤回する場合はステータスを `Superseded by ADR-YYYY` とし、ファイルは残す）。

## フォーマット

各ADRは以下の構成を持つ。

- **ステータス**: Accepted / Superseded / Deprecated
- **コンテキスト**: 参照した `docs/design/*.md` の章節と、そこで何が未確定だったか
- **決定**: 採用した方針
- **影響（Consequences）**: この判断によって何が制約されるか、将来見直す条件、未決定のまま残る事項

## 未着手・保留中の論点

`docs/design-review.md` の「決着状況」セクション参照。ADR化せず推奨案のまま実装を進める曖昧点（大半のA1〜A20系）と、P2以降で個別ADR化する予定の論点（Quota予約のcommit/release欠落など）がある。
