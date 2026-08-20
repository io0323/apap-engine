# ADR-0008: CredentialRef状態モデルを4状態（09章）で正とする

## ステータス

Accepted（2026-08-20）

## コンテキスト

`docs/design/04_ドメイン設計.md` 4.3.1 は `CredentialRef` VOの状態を `state(ACTIVE/STANDBY/REVOKED)` の3状態と記載している。一方 `docs/design/09_状態遷移図.md` 9.7 は `STANDBY → ACTIVE → REVOKED_PENDING → REVOKED` の4状態を定義しており、**`REVOKED_PENDING` が4.3.1の記述から欠落している**（`docs/design-review.md` #8, C1 参照）。両者は同一Aggregate内の同一属性についての矛盾した記述である。

## 決定

**9.7の4状態を正とする。** `REVOKED_PENDING` は装飾ではなく**機能上必須**である。

理由: ローテーション実行の瞬間、旧Credentialを使って**すでに送信中のリクエスト**が存在する。旧Credentialを即座に `REVOKED` にすると、これらのリクエストが `AUTH_ERROR` で失敗する。`docs/design/02_システム仕様.md` 2.11のエラー分類では `AUTH_ERROR` は同一候補内Retry不可・Fallback対象であるため、ローテーションのたびに**他Providerへのフェイルオーバーが多発**する。猶予期間（既定24h）を持つ `REVOKED_PENDING` 状態は、これを防ぐために必要である。

4.3.1の不変条件「ACTIVEは常に1つ」は4状態でも維持される（ACTIVE 1つ + STANDBY 0..n + REVOKED_PENDING 0..n + REVOKED 0..n）。この不変条件はテストで固定すること。

## 影響（Consequences）

- `docs/design/04_ドメイン設計.md` 4.3.1 は編集しない（設計書は一次情報として維持する。`01_CLAUDE.md` 不変条件8に従う）。実装上の `CredentialRef` VOは9.7の4状態で実装し、本ADRをコード上の参照根拠とする。
- **見直す条件**: なし。設計書の記述漏れの補完であり、将来の要件変更で見直す性質のものではない。
- **関連**: `docs/design-review.md` #8 / C1、[ADR-0002](ADR-0002-secret-store-responsibility-boundary.md)（Rotation手順の責務分界）。
