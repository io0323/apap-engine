# ADR-0007: Batch結果保持期間の起算点（terminal_at）とデータ種別ごとの保持ポリシー整理

## ステータス

Accepted（2026-08-20）

## コンテキスト

`docs/design/09_状態遷移図.md` 9.5 は「結果保持期間（既定7日）」とのみ記載し起算点が不明瞭だった（`docs/design-review.md` #7, A15 参照）。Conversation保持（既定90日、`docs/design/01_要件定義.md` NFR-DAT-001）やAudit保持（既定400日、NFR-SEC-003）との混同が起きやすい。

## 決定

**`terminal_at`（終端時刻）起算7日、テナント設定可**とする。`completed_at` ではなく `terminal_at` とし、`COMPLETED` / `FAILED` / `CANCELLED` いずれの終端状態でも記録する（`COMPLETED` のみを起算点にすると `FAILED` ジョブの部分結果が永久に残ってしまうため）。

データ種別ごとの保持ポリシーを以下のとおり明示する。

| データ | 保持期間 | 根拠 |
|---|---|---|
| Batch結果ペイロード（`BATCH_ITEM.result_payload`） | `terminal_at` + 7日（テナント設定可） | 利用者の取得猶予 |
| Batchジョブメタデータ（状態・件数・時刻） | 既定90日 | 運用照会用 |
| Usage / Cost 記録 | 集計保持ポリシーに従う | 課金根拠 |
| Audit 記録 | 400日（NFR-SEC-003） | 監査要件 |

7日で消えるのは結果本体のみで、監査証跡は残る。この区別を実装とドキュメント両方で明示すること。

## 影響（Consequences）

- **制約**: `BatchJob` Aggregate（`docs/design/04_ドメイン設計.md` 4.3.5、`docs/design/12_ER図.md` の `BATCH_JOB` エンティティ）に `terminal_at` フィールドを追加する。現行の設計書には未定義のため実装時に追加すること。
- **見直す条件**: テナントごとの保持期間カスタマイズ要求が出た場合、`QuotaPolicy` 同様にテナント設定として拡張する。
- **関連**: `docs/design-review.md` #7 / A15。
