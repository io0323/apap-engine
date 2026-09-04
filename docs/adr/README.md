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
| [ADR-0026](ADR-0026-event-sourced-aggregate-reconstruction.md) | Event Sourcing対象Aggregateの再構築方式（apply/fold構造、イベントpayload拡張、スナップショット取得ポリシー） | Accepted | 補足（NFR-DAT-003、ADR-0014が委譲した未決定事項の解消） |
| [ADR-0027](ADR-0027-gateway-endpoint-catalog-and-not-implemented-code.md) | 13.1のエンドポイントのうち提供していないものを明示的に区別する（EndpointCatalog + NOT_IMPLEMENTED） | Accepted | 補足（P10着手、13.1、13.4） |
| [ADR-0028](ADR-0028-sse-message-end-omits-finish-reason.md) | SSEの`message_end`は`finish_reason`を省略する（エンジンが終了理由を伝播していないため） | Accepted | 補足（P10着手、13.3、2.10、FR-CAP-004） |
| [ADR-0029](ADR-0029-host-compatibility-verification-module.md) | 埋込ホスト互換性を検証する専用モジュールを置き、統合ドキュメントのコード例をそこでコンパイルする | Accepted | 補足（P10後続、P9統合ガイドの不具合） |
| [ADR-0030](ADR-0030-resource-not-found-error-code-for-admin-apis.md) | Admin系リソースの「存在しない」に汎用の `RESOURCE_NOT_FOUND` を追加する | Accepted | 補足（13.4、13.1 Admin系） |
| [ADR-0031](ADR-0031-canonical-request-loses-role-and-template-reference.md) | CanonicalRequestが13.2の入力表現（role / Template参照）を取りこぼしている | Accepted | 補足（P11総合検証、FR-CAP-001、FR-PMT-004） |
| [ADR-0032](ADR-0032-scheduler-execution-host.md) | Schedulerの実行主体を埋込ホストへ委譲する（Port化） | Accepted | 補足（P11総合検証、FR-EXE-006とその従属要件） |
| [ADR-0033](ADR-0033-audit-search-requires-tenant-scope.md) | 監査ログ検索はテナントスコープを必須にする | Accepted | 補足（P11総合検証、FR-SEC-003、FR-SEC-006） |
| [ADR-0034](ADR-0034-gateway-phase-overhead-measurement-point.md) | Gateway層に付加レイテンシの計測点を置く | Accepted | 補足（P11総合検証、NFR-PRF-001/002、2.19） |
| [ADR-0035](ADR-0035-tenant-rate-limit-has-no-source-of-truth.md) | テナント別レート制限に設定元が無い（既定バケットは絞らない） | Accepted | 補足（P12是正、FR-EXE-003、NFR-PRF-003） |
| [ADR-0036](ADR-0036-lock-free-rate-limiter-and-circuit-breaker.md) | Rate LimiterとCircuit Breakerのロック競合への対処方針（CAS化は単独では採らない） | Accepted | 補足（P13、NFR-PRF-003、2.4/2.12） |
| [ADR-0037](ADR-0037-content-filtered-is-a-response-not-an-error.md) | コンテンツ拒否を例外側と応答側のどちらで表現するか | Proposed（実装は次フェーズ） | 補足（P15 SPI検証、FR-CAP-003） |
| [ADR-0038](ADR-0038-adapter-credential-ref-resolution.md) | AdapterがどのCredentialRefを使うべきかをSPIが伝えていない | Proposed（実装は次フェーズ） | 補足（P15 SPI検証、FR-SEC-002） |
| [ADR-0039](ADR-0039-modality-declaration-in-capability-constraints.md) | 対応modalityを申告する手段がSPIに無い | Proposed（実装は次フェーズ） | 補足（P15 SPI検証、FR-RTE-002） |
| [ADR-0040](ADR-0040-required-and-unsupported-generation-params.md) | Provider必須パラメタと未対応パラメタをSPIが表現できない | Proposed（実装は次フェーズ） | 補足（P15 SPI検証、FR-CAP-001） |

## 命名規則

`ADR-XXXX-kebab-case-title.md`。番号は4桁ゼロ埋め連番、欠番・再利用はしない（撤回する場合はステータスを `Superseded by ADR-YYYY` とし、ファイルは残す）。

## フォーマット

各ADRは以下の構成を持つ。

- **ステータス**: Accepted / Proposed / Superseded / Deprecated
  （`Proposed` は「論点として確定したが、実装は後続フェーズ」。P15のSPI検証で起票した
  ADR-0037〜0040がこれにあたる——1つのProviderの都合でSPIを変えると2つ目で歪むため、
  2つ目のAdapterを別Providerで書いてから確定させる）
- **コンテキスト**: 参照した `docs/design/*.md` の章節と、そこで何が未確定だったか
- **決定**: 採用した方針
- **影響（Consequences）**: この判断によって何が制約されるか、将来見直す条件、未決定のまま残る事項

## 未着手・保留中の論点

`docs/design-review.md` の「決着状況」セクション参照。ADR化せず推奨案のまま実装を進める曖昧点（大半のA1〜A20系）と、P2以降で個別ADR化する予定の論点（Quota予約のcommit/release欠落など）がある。
