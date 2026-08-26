# Masking Strategy（PIIマスキング）についての注意

`apap.observability.audit.MaskingStrategy` は16_拡張ポイント.md 16.6のSPIであり、Audit本文保存
opt-in時に本文へ適用するマスキング処理を差し替え可能にする。同梱の既定実装
`apap.observability.audit.RegexMaskingStrategy` は、メールアドレス・電話番号・クレジットカード
番号・IPv4アドレスに対する汎用的な正規表現パターンを提供する。

## 重要: 正規表現ベースのマスキングは完全ではない

`RegexMaskingStrategy`（および一般に正規表現ベースのPII検出）には以下の既知の限界がある。

- 文脈依存のPII（人名、住所、組織固有のID体系など）は検出できない。
- パターンに一致しない表記ゆれ（区切り文字の違い、他言語表記、意図的な難読化）を見逃す。
- 過検出（マスキング不要な数値列などを誤って置換する）・過小検出のいずれも起こり得る。
- 新種のPII形式（新しいトークン形式、社内固有の識別子等）には追従しない。

**したがって、`RegexMaskingStrategy`（または類似の正規表現ベース実装）を使用していることをもって、
GDPR・CCPA等のプライバシー関連コンプライアンス要件を充足していると主張してはならない。** 本文保存
opt-in（`AuditConfig.bodyStorageOptIn`）を有効化するテナント・運用者は、自身のコンプライアンス
要件に応じて、より厳密なマスキング実装（ML/NLPベースのPII検出等）を`MaskingStrategy`実装として
差し替えることを検討すること。

## 既定の安全策

- 本文保存は既定OFF。`AuditRecord.requestBody`/`responseBody`相当のフィールドは、opt-inしない限り
  常に`null`（ハッシュ値のみ保持）。
- `AuditConfig.bodyStorageOptIn = true`かつ`maskingStrategy`が未設定の場合、`AuditEngine`の構築時点
  で例外を送出し、マスキングなしでの本文保存を機械的に防止する。
