# ADR-0003: 初期対応トランスポートプロトコルのスコープ（REST/SSEのみ）

## ステータス

Accepted（2026-08-20）

## コンテキスト

`docs/design/01_要件定義.md` 1.3.1 は利用者ごとの接続方式として REST / gRPC / SSE / SDK / WebSocket / MQTT Bridge を挙げているが、`docs/design/13_API設計.md` は REST + SSE のみを仕様化しており、gRPC / WebSocket / MQTT の仕様は設計書のどこにも存在しない（`docs/design-review.md` #3, U8, U9, U10 参照）。全プロトコルを初期実装すると着手範囲が広がりすぎる。

## 決定

- **REST + SSEを正典（normative）とする。** `docs/design/13_API設計.md` が唯一の契約仕様。
- **gRPC**は同一UseCase層に対する代替トランスポート（`docs/design/03_基本設計.md` 3.9 Protocol Adapterパターン）として将来対応する。初期実装では作らない。
- **WebSocket**はSSEで要件（サーバ→クライアントの単方向ストリーム）を充足できるため、双方向要求が具体的に出るまで実装しない。
- **MQTT Bridge**はAPAPの対象外とする。IoTデバイス ↔ MQTT ↔ REST の変換は独立したエッジコンポーネントの責務とする。1.3.1の記述は「その経路を経て最終的にAPAPへ到達する」という意味に読み替える。

## 影響（Consequences）

- **制約**: `apap-gateway` 以外のモジュールにトランスポート固有のロジックを持ち込まない（UseCase層のプロトコル非依存を維持する。`docs/design/02_システム仕様.md` 2.2.2 の依存規則に従う）。
- **見直す条件**: gRPC/WebSocketの実需が具体的な利用者から出た時点で、Protocol Adapterパターンに従い薄い層として追加する。MQTT対応が必要になった場合は別プロジェクトとして切り出す。
- 主成果物 `modules/apap-runtime`（ライブラリ）はトランスポートを必要としないため、本スコープ限定は主目的（prompt-engineへの組込）の達成を妨げない。
- **関連**: `docs/design-review.md` #3 / U8 / U9 / U10。
