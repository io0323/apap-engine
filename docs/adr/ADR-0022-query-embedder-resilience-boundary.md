# ADR-0022: QueryEmbedderの実装位置とResilience機構からの意図的な分離

## ステータス

Accepted（2026-08-26）

## コンテキスト

`apap.context.QueryEmbedder`（02_システム仕様.md 2.17: 今回入力をMemory類似検索用のベクトルへ
変換する）は、P6時点で`NoOpQueryEmbedder`（常に空ベクトル、明示的opt-in必須、構築時WARN）の
みが存在し、実ベクトル化はP7以降の対象として保留されたまま、この判断の根拠を記録するADRが
起票されていなかった（P7着手前レビューで指摘）。

実ベクトル化を素朴に実装しようとすると、モジュール依存方向上の制約に突き当たる。

- `apap-context`は`apap-domain`のみに依存できる（CLAUDE.md不変条件2）。実ベクトル化は
  Provider Adapter（`apap-adapter-spi`/`apap-provider`）へのI/O呼出を要するため、
  `apap-context`自身がAdapterを直接呼ぶには依存を追加する必要がある。
- 仮に`apap-context`が`apap-provider`へ依存できたとしても（Gradle上のサイクルは生じない）、
  そのAdapter呼出をRetry/Circuit Breaker/Rate Limiter（03_基本設計.md 3.3.6、実体は
  `apap-execution`の`AttemptExecutor`/`CircuitBreaker`と`apap-cache.ratelimit.RateLimiter`）
  でラップしようとすると、`apap-execution`が`apap-context`に依存しているため
  （`ExecutionEngine`が`ContextManager.build`を呼ぶ、ADR-0021と同じ経路）、
  `apap-context`が`apap-execution`のResilience機構を呼び返す構図は循環依存になる。

Memory注入のための埋め込み呼出を、メインリクエストの実行と同じ堅牢性（Retry/CB/RateLimit）
で保護したいという要求と、依存方向の制約は両立しない。

## 決定

QueryEmbedderの実装は`apap-execution`のResilience機構（AttemptExecutor/CircuitBreaker/
RateLimiter）を共有**しない**。

- `apap.context.QueryEmbedder`インターフェース自体は変更しない
  （`fun embed(parts: List<ContentPart>): List<Double>`のまま）。
- 実装（P8以降）はコンポジションルート（`apap-runtime`、あらゆるモジュールに依存できる）で
  組み立てる。`AdapterRegistry`から埋め込み用Provider/Modelを解決し、直接
  `ProviderAdapter`を呼ぶ。呼出には独立した軽量なタイムアウト/リトライを持たせてよいが、
  メインリクエストのCircuit Breaker状態・Rate Limiterバケットとは状態を共有しない
  （埋め込み専用の失敗がメインリクエストのCB/RateLimit判定へ波及しない、という意味でも
  むしろ望ましい分離）。
- 埋め込み呼出が失敗またはタイムアウトした場合、例外を投げず空ベクトル（`emptyList()`）を
  返す。`DefaultContextManager`は既にこれを「Memory注入なし」として扱う既存の契約
  （`NoOpQueryEmbedder`のKDoc参照）と一致するため、`ContextManager`側の変更は不要。
  Memory注入はベストエフォートであり、メイン応答の成否に影響させない。

## 影響（Consequences）

- **担当フェーズ**: 実ベクトル化の実装はP8以降とする（本ADRはP7着手前レビューで発覚した
  「ADR未起票」の是正であり、実装そのものは引き続き対象外）。
- **制約**: 将来の実装は`apap-context`へ`apap-provider`/`apap-adapter-spi`依存を追加しない。
  Provider呼出はすべて`apap-runtime`が組み立てるQueryEmbedder実装クラスの内部に閉じる。
- **トレードオフ**: 埋め込み呼出はメインリクエストと同水準のCB/Retry保護を受けない。
  Provider障害時にMemory注入が静かに欠落する可能性があるが、応答が失敗するよりは安全側
  （ベストエフォート）とする。
- **見直す条件**: 将来Memory注入の可用性がメイン応答と同等に重要になった場合、
  `ExecutionEngine`側で埋め込み呼出を`AttemptExecutor`経由の別ステップとして実行し、
  結果のベクトルを`ContextManager.build`へ引数として渡す設計（`ContextManager`自身は
  埋め込みをトリガーしない）へ変更することを検討する。その場合は`ContextManager.build`の
  シグネチャ変更を伴う。
- **関連**: `apap.context.QueryEmbedder`/`NoOpQueryEmbedder`（`modules/apap-context/src/main/kotlin/apap/context/QueryEmbedder.kt`）、
  FR-CTX-004（Memory）、02_システム仕様.md 2.17。
