# 15. Provider追加手順

AI Provider Abstraction Platform（APAP）設計書 第15章

---

## 15.1 新しいProvider追加

前提: APAPコアの変更・再デプロイは**不要**（NFR-EXT-001）。所要成果物はAdapter Pluginと登録設定のみ。

### Step 1: Adapter Plugin開発

1. `apap-adapter-spi` を依存に追加（これ**のみ**。コアモジュールへの依存禁止）。
2. `ProviderAdapter` interface（3.3.2）を実装する。実装必須メソッド:
   - `initialize / shutdown / spiVersion`
   - `supportedCapabilities / capabilityConstraints`
   - `authenticate / validateCredential`
   - `execute / executeStream`（対応Capabilityの範囲）
   - `translateTools`（tool_calling/function_calling対応時）
   - `discoverModels / healthCheck`
   - `fetchUsage / fetchCost`（Provider側API提供時。なければ `null` 返却可）
3. 実装規約:
   - Provider固有のURL・認証・リクエスト/レスポンス形式・エラーコードはAdapter内へ完全に閉じ込める。
   - 例外は必ず `AdapterException`（分類: TRANSIENT / RATE_LIMITED / INVALID_REQUEST / AUTH_ERROR / CONTENT_FILTERED / MODEL_ERROR / PROVIDER_UNAVAILABLE / UNSUPPORTED_CAPABILITY）へ変換して送出。分類が2.11のRetry/Fallback挙動を決める。
   - Credentialは `SecretAccessor.resolve()` で都度取得。フィールド保持・ログ出力禁止。
   - Provider側Retry機構は使用しない（Retry EngineがAPAP側で一元制御）。
   - Streamは pull型 `AdapterStream`（next/cancel）で実装。`cancel()` でProvider接続を確実に切断。
   - タイムアウトは `AdapterRequest.timeout` を厳守。

### Step 2: Pluginマニフェスト作成

```yaml
# plugin.yaml
plugin_id: adapter-example-a
version: 1.0.0
spi_version: ">=1.2 <2.0"        # 適合SPI範囲
entry_point: example.a.ExampleAdapter
capabilities: [chat, embedding, streaming, tool_calling]
auth_types: [api_key]
signature: <配布パッケージ署名>
```

### Step 3: テスト

1. SPI準拠テストキット（apap-adapter-spi同梱のContract Test）を全件パスさせる。
   - Capability毎の入出力正規化、エラー分類、Stream中断、タイムアウト、Credential非漏出。
2. Sandboxテナントで結合テスト（record/replay可能なProviderスタブ推奨）。

### Step 4: 配置・登録・有効化（運用者）

| # | 操作 | API / 動作 |
|---|---|---|
| 1 | Plugin配置 | `plugin.dir` へ配置 or レジストリpush → `POST /admin/v1/plugins:scan` |
| 2 | Plugin検証 | Plugin Managerが署名検証・SPI適合確認 → `PluginLoaded`（不合格は `PluginQuarantined`） |
| 3 | Credential登録 | Secret Storeへ登録（APAPは参照キーのみ保持） |
| 4 | Provider登録 | `POST /admin/v1/providers` {name, adapter_plugin_id, endpoints, auth_type, rate_limits, regions, priority} → REGISTERED |
| 5 | 検証 | `POST /admin/v1/providers/{id}:validate` → Credential検証+疎通+Capability申告確認 → VALIDATING→合格 |
| 6 | Model登録 | Step 5後の `discoverModels` 結果を承認 or 手動登録 → TESTING |
| 7 | テスト流量 | Alias weightでTESTING Modelへ少量流す（例5%）→ メトリクス確認 |
| 8 | 有効化 | `POST .../providers/{id}:enable` + Model ACTIVE化 → `ProviderEnabled` 発火 → Routing候補へ自動反映 |

**利用側アプリケーションの変更: ゼロ**（Capability/Aliasのみ参照のため）。

## 15.2 新しいModel追加

前提: Providerは登録済。コード変更**不要**（NFR-EXT-002）。

1. 検出: 日次Discovery（`ModelDiscovered`）または手動で把握。
2. 登録: `POST /admin/v1/models` { provider_id, model_name, version, capabilities[], context_window, max_output_tokens, regions } → REGISTERED → TESTING。
3. 単価登録: PriceBookへ `PRICE_ENTRY` 追加（effective_from指定）。**未登録の場合ルーティングのコストスコアが計算不能のため登録必須**。
4. 検証: TESTING状態でベンチマーク/Contractテスト流量を実行。
5. 有効化: `PATCH /admin/v1/models/{id}` status=ACTIVE。
6. 展開: Alias weight操作でCanary移行（10%→50%→100%、5.8参照）。問題発生時はweightを即時0%へ戻す（ロールバックはAlias操作のみ）。
7. 旧Model退役: DEPRECATED → Alias参照ゼロ確認 → RETIRED。

## 15.3 新しいCapability追加

影響範囲: Capability Registry + 対応Adapter + （必要なら）Gatewayエンドポイント。**既存Capability利用者へは無影響**（NFR-EXT-003）。

1. **スキーマ定義**: CapabilityDefinition { capability_id, input_schema, output_schema, streamable } をJSON Schemaで定義し、`POST /admin/v1/capabilities`（Capability Registry）へ登録。status=PREVIEWで開始。
2. **共通DTO拡張**: `CapabilityInput / CapabilityOutput` のUnionへ型追加（apap-api。汎用エンドポイント `POST /v1/execute` を使う場合はスキーマ登録のみで完結し、この手順は省略可能。専用RESTパスが必要な場合のみGatewayへルート追加）。
3. **Mapper拡張点**: Request/Response Mapperは Capability Registryのスキーマ駆動で汎用変換するため、標準構造であれば変更不要。特殊変換が必要な場合は `CapabilityMapperExtension` SPIを実装。
4. **Adapter対応**: 対応可能なAdapterが `supportedCapabilities()` へ追加し、`execute` 分岐を実装。Adapter更新はPlugin差替（ローリング）で反映。
5. **Contract Test追加**: SPIテストキットへ新Capabilityの準拠テストを追加。
6. **公開**: 検証後 status=GA。Discovery API（`GET /v1/capabilities`）へ自動反映され、利用側が発見可能になる。
7. **Policy/Quota**: 必要に応じ新Capabilityへのテナント権限・Quotaを設定。

## 15.4 チェックリスト（Go-Live判定）

- [ ] Contract Test全件パス（エラー分類・Stream中断・Credential非漏出含む）
- [ ] Health Check応答が30秒周期で安定
- [ ] 単価（PriceBook）登録済・コスト算出がAuditへ反映
- [ ] Fallback Chainに組み込んだ場合の切替動作確認（強制障害試験）
- [ ] Rate Limit設定がProvider実制限以下
- [ ] Canary 5%で24時間、エラー率・レイテンシがSLO内
- [ ] ロールバック手順（Alias weight 0%化）の演習済
