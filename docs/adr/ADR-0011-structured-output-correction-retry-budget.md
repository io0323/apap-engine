# ADR-0011: Structured Output是正リトライと通常Retry予算の統合

## ステータス

Accepted（2026-08-20）

## コンテキスト

`docs/design/02_システム仕様.md` 2.11のエラー分類表は `MODEL_ERROR`（出力Schema違反）を「同一候補Retry ○（是正プロンプトで最大2回）」と記載するが、`docs/design/03_基本設計.md` 3.3.5 の `AttemptExecutor` 疑似コードは是正リトライと通常リトライ（`maxAttempts` 既定3）を区別しておらず、両者の予算関係が不明瞭だった（`docs/design-review.md` #10, A10 参照）。

## 決定

1. Structured Outputの是正リトライは2.11の `MODEL_ERROR` 分類として扱う。
2. **`maxAttempts`（既定3）の内数**とする。是正2回＝初回＋是正2回＝計3試行で上限到達。別枠加算しない。
3. **タイムアウト予算も共有**する（2.11の予算管理に従う）。
4. **是正回数はリクエスト全体で最大2回**とし、Fallbackで別候補へ移ってもリセットしない。
5. 是正時のプロンプトには「Schema違反の具体的内容」を追記する（同一プロンプトの単純再送は無意味なため）。プロンプトが変化するため、Response Cacheには是正後の結果のみを保存する。

## 影響（Consequences）

- **制約**: 是正回数のリセットを許すと `Fallback3段 × 是正2回 = 最大9回` のProvider呼出になり、コストが想定の約3倍に膨らむ。実装時にリセットロジックを入れないこと（`AttemptExecutor` / `FallbackEngine` の試行カウンタは `requestId` 単位でグローバルに保持する）。
- **見直す条件**: Structured Output成功率が低く3試行で頻繁に失敗する場合、`maxAttempts` の既定値自体をPolicyで引き上げる方向で対応し、是正回数の別枠化はしない。
- **関連**: `docs/design-review.md` #10 / A10。
