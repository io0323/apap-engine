# ADR-0002: Secret Store責務分界（外部Store採用、Rotation状態機械はAPAP自身の責務）

## ステータス

Accepted（2026-08-20）

## コンテキスト

`docs/design/03_基本設計.md` 3.15 の設定例に `secret.store: vault-compatible` という値の例示があるのみで、Secret Storeを自前実装するか外部Storeへ委譲するか、また `docs/design/01_要件定義.md` FR-SEC-002（Credential Rotation）の実装主体がAPAP自身か外部Storeかが設計書に明記されていない（`docs/design-review.md` #2, U13 参照）。

## 決定

秘密情報の**保管・保存時暗号化・アクセス制御・バージョン保持**は既存の外部Store（OSS/マネージド）へ委譲する。自前実装しない。ただし**APAPコアは特定製品を知ってはならない**。

**責務分界**:
- 外部Storeに委譲するもの: 保管、保存時暗号化、アクセス制御、バージョン保持。
- APAPが自ら実装するもの: Rotationの**状態機械**（`docs/design/09_状態遷移図.md` 9.7 の4状態遷移、[ADR-0008](ADR-0008-credential-ref-four-state-model.md) 参照）、検証付き切替の**手順**（新Credential検証 → STANDBY昇格 → 旧をREVOKED_PENDINGへ → 猶予後REVOKED）、`CredentialRotated` イベント発火、監査記録。

`docs/design/01_要件定義.md` FR-SEC-002 の「ローテーション」はAPAPの責務であり、外部Storeへ丸投げしない。外部Storeは「安全な置き場」としてのみ使う。

**実装**: `SecretStore` SPI + 実装3種。
1. `EnvVarSecretStore`（開発・テスト用、既定）
2. `ExternalSecretStore`（HTTP/gRPCで外部Storeを叩く汎用実装。エンドポイントと認証方式は設定）
3. 埋込時の宿主注入（`ApapEngineBuilder.secretStore()` で宿主アプリの秘密管理機構をそのまま利用可能）

具体的な製品選定はデプロイ時の決定であり、設計・実装をブロックしない。

## 影響（Consequences）

- **制約**: コード・設定・ログ・例外メッセージに特定Secret Store製品名を書いてはならない（`01_CLAUDE.md` 不変条件1のVendor Neutral原則と同様の精神をSecret Storeにも適用する）。
- **見直す条件**: 外部Store側がRotation機構を標準搭載し、それをAPAPの `CredentialRef` 状態機械（ADR-0008）へそのままマッピングできると判明した場合、実装主体を再検討してよい。
- **未決定**: 具体的な外部Store製品はデプロイ時決定。
- **関連**: `docs/design-review.md` #2 / U13、[ADR-0008](ADR-0008-credential-ref-four-state-model.md)。
