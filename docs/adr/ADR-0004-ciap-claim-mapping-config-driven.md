# ADR-0004: CIAP JWTクレームマッピングの設定駆動化

## ステータス

Accepted（2026-08-20）。CIAP側との実値合意は未了 — 下記「未決定」参照。

## コンテキスト

`docs/design/02_システム仕様.md` 2.8（Request Flow）は「CIAPトークン検証 → tenant解決」とのみ記載し、CIAP発行JWTのクレーム名・形式は設計書に定義がない（`docs/design-review.md` #4, U15 参照）。CIAPは外部基盤であり実値の確定にはCIAPチームとの合意が必要だが、実装をブロックすべきではない。

## 決定

`TokenVerifier` / `TenantResolver` をinterfaceとして腐敗防止層（ACL、`docs/design/04_ドメイン設計.md` 4.2のCIAP-ACLパターンに合致）に置き、クレーム名はハードコードせず設定ファイルで宣言する。

設定キー例（`apap.auth` 配下）:

```yaml
apap.auth:
  issuer: "<CIAP issuer URL>"
  audience: "<APAP audience>"
  jwks_uri: "<JWKS endpoint>"
  jwks_cache_ttl: 10m
  claims:
    tenant: "tenant_id"        # 実値はCIAPと合意後に差し替え
    principal: "sub"
    scopes: "scope"
    capabilities: "apap_capabilities"   # 任意。無い場合はPolicyで解決
  clock_skew: 60s
```

テストは自己署名JWTを生成するテストダブルで行い、CIAP実体に依存しない。

## 影響（Consequences）

- **未決定（CIAP側へ確認が必要な事項）**:
  1. issuer URL / JWKSエンドポイント / 鍵ローテーション周期
  2. APAP用 audience の発行可否
  3. テナント識別子のクレーム名と値の形式（ULID / UUID / 文字列）
  4. principal（`sub`）の形式と、テナント跨ぎの一意性保証の有無
  5. **Capability/Model単位の利用権限（FR-SEC-003）をJWTクレームで運ぶか、APAP側Policyで持つか** — JWT側に寄せると権限変更のたびにトークン再発行が必要になり運用が硬直する。APAP側Policyで持つほうが柔軟だが、CIAPが権限の一元管理を志向している場合は方針衝突が起きうる。**早期合意が必要な最重要項目。**
  6. トークン有効期限と、長時間Streaming（最大300s）中に失効した場合の扱い
  7. サービス間呼び出し（AACP → APAP）で使うトークン種別（ユーザー委譲 or サービスアカウント）
- **見直す条件**: CIAPとの合意が得られ次第、`claims.*` の既定値を確定し設定ファイルのデフォルトへ反映する。
- **関連**: `docs/design-review.md` #4 / U15。
