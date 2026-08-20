# ADR-0013: Audit request_digestへのテナント固有ソルト付与

## ステータス

Accepted（2026-08-20）

## コンテキスト

`docs/design/02_システム仕様.md` 2.18の `AuditRecord.request_digest`（`docs/design/12_ER図.md` の `AUDIT_RECORD.request_digest CHAR(64)` にも対応）は「本文は既定ハッシュのみ」と記載するが、ハッシュアルゴリズムおよびテナント跨ぎの衝突・推測対策が未定義だった（`docs/design-review.md` 補足 参照）。

## 決定

**SHA-256** を採用する。ただし**テナント固有のソルトを含めて**ハッシュを計算する（テナント跨ぎの衝突・推測によるAudit記録の相関悪用を防ぐため）。

## 影響（Consequences）

- **制約**: ソルトはテナントごとに管理し、Secret Store（[ADR-0002](ADR-0002-secret-store-responsibility-boundary.md)）相当の保護レベルで保持する。ソルト漏洩時はローテーション手順が必要になる（本ADRの範囲外、将来検討）。
- **見直す条件**: ソルトのローテーション要件が具体化した時点で、`CredentialRotationService`（[ADR-0008](ADR-0008-credential-ref-four-state-model.md)）と同様の手順を別途検討する。
- **関連**: `docs/design-review.md` 補足、[ADR-0002](ADR-0002-secret-store-responsibility-boundary.md)。
