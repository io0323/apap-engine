# 13. API設計（REST API）

AI Provider Abstraction Platform（APAP）設計書 第13章

共通事項: Base Path `/v1`（Admin系は `/admin/v1`）。認証は `Authorization: Bearer <CIAP発行JWT>`。Content-Type `application/json`（音声・画像バイナリは `multipart/form-data` も可）。全応答に `X-Request-Id` を付与。冪等化は `Idempotency-Key` ヘッダ。

---

## 13.1 エンドポイント一覧

### 実行系API

| Method | Path | 概要 |
|---|---|---|
| POST | /v1/chat | Chat（`stream=true` でSSE） |
| POST | /v1/completions | Completion |
| POST | /v1/embeddings | Embedding生成 |
| POST | /v1/images/generations | 画像生成 |
| POST | /v1/images/edits | 画像編集 |
| POST | /v1/images/analyses | 画像解析 |
| POST | /v1/audio/transcriptions | Speech To Text |
| POST | /v1/audio/speech | Text To Speech |
| POST | /v1/audio/translations | Audio Translation |
| POST | /v1/videos/analyses | 動画解析 |
| POST | /v1/batches | Batchジョブ投入 |
| GET | /v1/batches/{job_id} | Batch状態取得 |
| GET | /v1/batches/{job_id}/results | Batch結果取得 |
| DELETE | /v1/batches/{job_id} | Batchキャンセル |
| POST | /v1/memories / GET・DELETE /v1/memories/{id}, POST /v1/memories/search | Memory操作 |

### Discovery / Context系API

| Method | Path | 概要 |
|---|---|---|
| GET | /v1/capabilities | 利用可能Capability一覧（テナント権限適用済） |
| GET | /v1/capabilities/{capability_id} | Capability詳細（入出力JSON Schema、制約） |
| GET | /v1/aliases | 利用可能Model Alias一覧 |
| POST | /v1/sessions / DELETE /v1/sessions/{id} | Session作成・失効 |
| POST | /v1/conversations | Conversation作成 |
| GET | /v1/conversations/{id} / GET .../turns | 取得・履歴取得 |
| DELETE | /v1/conversations/{id} | 削除（論理→物理） |

### 管理系API（Admin権限）

| Method | Path | 概要 |
|---|---|---|
| POST / GET | /admin/v1/providers | Provider登録 / 一覧 |
| GET / PATCH / DELETE | /admin/v1/providers/{id} | 取得 / 更新 / 論理削除 |
| POST | /admin/v1/providers/{id}:enable \| :drain \| :disable \| :validate | 状態操作 |
| POST | /admin/v1/providers/{id}/credentials:rotate | Credential Rotation |
| POST / GET | /admin/v1/models | Model登録 / 一覧 |
| PATCH | /admin/v1/models/{id} | 更新（status含む） |
| GET | /admin/v1/models:discovered | Discovery検出済み候補一覧 |
| PUT / GET | /admin/v1/aliases/{name} | Alias付替（Canary weight） / 取得 |
| POST / GET / PUT | /admin/v1/policies | Routing Policy管理 |
| PUT | /admin/v1/quotas/{tenant_id} / /admin/v1/budgets/{tenant_id} | Quota / Budget設定 |
| GET | /admin/v1/analytics/usage \| /cost \| /errors | 集計（期間・軸指定） |
| GET | /admin/v1/audit | 監査検索 |
| GET | /admin/v1/health/providers | Provider Health集約 |
| POST | /admin/v1/plugins:scan / GET /admin/v1/plugins | Plugin管理 |
| POST | /admin/v1/caches:invalidate | Cache無効化 |

## 13.2 Request仕様（代表）

### POST /v1/chat

```json
{
  "model_alias": "chat-standard",
  "messages": [
    { "role": "system", "content": [{ "type": "text", "text": "あなたはアシスタントです" }] },
    { "role": "user", "content": [{ "type": "text", "text": "こんにちは" }] }
  ],
  "params": {
    "temperature": 0.7, "max_tokens": 1024, "top_p": 1.0,
    "stop": [], "seed": null
  },
  "tools": [
    { "name": "get_weather",
      "description": "天気取得",
      "input_schema": { "type": "object", "properties": { "city": { "type": "string" } }, "required": ["city"] } }
  ],
  "tool_results": null,
  "output_schema": null,
  "stream": false,
  "conversation_id": "01J...",
  "session_id": "01J...",
  "constraints": { "region": "jp", "max_cost": 0.05, "exclude_providers": [] },
  "preferences": { "optimize_for": "balanced" },
  "metadata": { "workflow_id": "wf-123" }
}
```

制約: `messages` 必須（1..1000件）。`model_alias` 省略時はテナント既定Alias。`content` はContentPart配列（`text` / `image` / `audio`。Capability制約はDiscovery APIで取得可能）。ProviderやModelの物理名は指定**不可**（Vendor Neutral原則）。

### POST /v1/embeddings

```json
{ "model_alias": "embedding-standard", "inputs": ["文1", "文2"], "dimensions": 1024 }
```

### POST /v1/batches

```json
{
  "target_capability": "embedding",
  "requests": [ { "custom_id": "r1", "body": { "inputs": ["..."] } } ],
  "completion_webhook": "https://app.example.internal/hook",
  "priority": "normal"
}
```

## 13.3 Response仕様（代表）

### 200 OK（Chat）

```json
{
  "response_id": "01J...",
  "request_id": "01J...",
  "output": {
    "message": { "role": "assistant",
      "content": [{ "type": "text", "text": "こんにちは！..." }] }
  },
  "tool_calls": null,
  "finish_reason": "completed",
  "usage": { "input_tokens": 25, "output_tokens": 180, "total_tokens": 205, "estimated": false },
  "cost": { "amount": 0.00123, "currency": "USD" },
  "cached": false,
  "model_alias": "chat-standard",
  "metadata": {}
}
```

注: 応答にも物理Provider/Model名は既定で含めない（`metadata` 開示ポリシーで opt-in 可。Auditには常に記録）。

### Tool Calling応答（finish_reason=tool_call）

```json
{
  "tool_calls": [
    { "id": "tc_01", "name": "get_weather", "arguments": { "city": "Tokyo" } }
  ],
  "finish_reason": "tool_call"
}
```

### SSE Streaming（stream=true）

```
event: message_start
data: {"response_id":"01J...","index":0}

event: content_delta
data: {"index":0,"delta":{"type":"text","text":"こん"}}

event: content_delta
data: {"index":0,"delta":{"type":"text","text":"にちは"}}

event: usage
data: {"input_tokens":25,"output_tokens":180,"total_tokens":205}

event: message_end
data: {"finish_reason":"completed"}
```

異常時は `event: error`（bodyは13.4のエラー形式）で終端。15秒毎に `event: heartbeat`。

## 13.4 Error仕様

エラー応答形式（RFC 9457 Problem Details準拠 + 拡張）:

```json
{
  "type": "https://apap.example.internal/errors/rate-limit-exceeded",
  "title": "Rate limit exceeded",
  "status": 429,
  "code": "RATE_LIMIT_EXCEEDED",
  "detail": "Tenant request rate exceeded (limit: 100 rpm)",
  "request_id": "01J...",
  "retryable": true,
  "retry_after_ms": 2000
}
```

### エラーコード体系

| code | HTTP | retryable | 説明 |
|---|---|---|---|
| INVALID_REQUEST | 400 | false | 入力スキーマ違反 |
| PROMPT_VALIDATION_FAILED | 400 | false | Prompt検証不合格（サイズ/禁止パターン） |
| UNAUTHENTICATED | 401 | false | トークン無効・期限切れ |
| PERMISSION_DENIED | 403 | false | Capability/Model権限なし・Policy DENY |
| CAPABILITY_NOT_AVAILABLE | 404 | false | 対応候補なし（Capability未提供） |
| ALIAS_NOT_FOUND | 404 | false | Alias未定義 |
| CONVERSATION_NOT_FOUND | 404 | false | — |
| CONFLICT | 409 | false | 冪等キー重複（処理中）・楽観ロック競合 |
| PAYLOAD_TOO_LARGE | 413 | false | 入力上限超過 |
| CONTEXT_LENGTH_EXCEEDED | 422 | false | 圧縮後もcontext window超過（TokenLimitExceeded発火） |
| OUTPUT_SCHEMA_VIOLATION | 422 | false | Structured Output是正失敗 |
| CONTENT_FILTERED | 422 | false | Providerセーフティ拒否（正規化済） |
| RATE_LIMIT_EXCEEDED | 429 | true | テナント流量超過（RateLimitExceeded発火） |
| QUOTA_EXCEEDED | 429 | false | 期間Quota/Budget超過（QuotaExceeded発火） |
| INTERNAL_ERROR | 500 | true | APAP内部エラー |
| PROVIDER_ERROR | 502 | true | 全候補失敗（最終エラー正規化） |
| NO_CANDIDATE_AVAILABLE | 503 | true | フィルタ後候補ゼロ（全CB Open等） |
| TIMEOUT | 504 | true | タイムアウト予算超過 |

## 13.5 HTTP Status運用

| Status | 用途 |
|---|---|
| 200 | 同期成功（SSEも200で開始） |
| 201 | 作成（sessions / conversations / providers / batches等） |
| 202 | 非同期受理（batch投入、rotation開始） |
| 204 | 削除成功 |
| 4xx/5xx | 13.4のマッピングに従う |

追加ヘッダ: 429/503には `Retry-After`。全応答に `X-Request-Id`。Streamingは `Cache-Control: no-store`。
