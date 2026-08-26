# ADR-0023: QueryEmbedderはメインリクエストと同じCircuit Breaker/Rate Limiterを経由する（ADR-0022のSupersede）

## ステータス

Accepted（2026-08-26）

## コンテキスト

[ADR-0022](ADR-0022-query-embedder-resilience-boundary.md)は「QueryEmbedderの実装は`apap-execution`の
Resilience機構（AttemptExecutor/CircuitBreaker/RateLimiter）を共有しない」と決定していたが、
その根拠（`apap-context`が`apap-execution`に依存できないため循環依存になる）は誤りだった
（P8着手前レビューで指摘）。

`apap-context`自身が`apap-execution`のResilience機構を呼べないのは事実だが、QueryEmbedderの
**実装**は`apap-context`に置く必要がない。ADR-0022自身が既に「実装（P8以降）はコンポジション
ルート（`apap-runtime`）で組み立てる」と決定しており、`apap-runtime`は依存方向の制約を受けない
（あらゆるモジュールに依存できる配線層）。つまり`apap-runtime`に置く実装クラスは、同じ
`ExecutionEngineComposer.build()`内で構築済みの`CircuitBreaker`/`RateLimiter`インスタンスを
そのまま受け取って使え、循環依存は生じない。

この誤りは実務上重要である。Memory注入は02_システム仕様.md 2.17によりチャット実行のたびに
類似検索が走る高頻度経路であり、これを保護なしにProviderへ到達させると次の問題が生じる。

- RateLimiterを経由しないと、Providerのレート制限（契約上の上限）を素通りで突き抜ける。
- CircuitBreakerを経由しないと、障害中のProviderを毎リクエスト叩き続ける（Retry Stormと
  同種の問題）。

## 決定

QueryEmbedderの実装（P8以降）は、メインリクエストが使うのと同じ`CircuitBreaker`/`RateLimiter`
インスタンスを経由する。ADR-0022の他の決定（実装はコンポジションルートに置く、失敗時は空
ベクトルへ縮退しメイン応答を失敗させない）は維持する。

- **CircuitBreaker**: 共有かdedicatedかは、[apap.domain.model.vo.CbKey]が既に`(providerId, modelId)`
  のみで構成される（04_ドメイン設計.md 4.4）ことにより実質的に解決される。埋め込み用の
  Provider/Modelは通常メインのchat用Candidateとは異なるため、CB状態は追加の仕組みなしに
  自然に分離される。同一(provider, model)が両方の用途に使われる特殊ケースでは意図的にCB状態を
  共有する（その方がそのProvider/Modelの実態を正しく反映するため）。新たな「Capability軸」を
  CbKeyへ追加する変更（04_ドメイン設計.md 4.4の変更）は行わない。
- **RateLimiter**: テナントスコープとProviderスコープの両方を適用する。ADR-0012（キャッシュ
  短絡時）がテナントスコープのみとしたのは「Providerへ到達しない」ことが理由であり、埋め込み
  呼出は実際にProviderへ到達するため同じ扱いはできない。
- **QueryEmbedder.embed()をsuspendへ変更する**: ADR-0022は「インターフェース自体は変更しない」
  としていたが、Circuit Breaker/Rate Limiterを正しく経由する実装はProvider Adapterへの
  suspend I/O呼出を必要とし、非suspendのままでは`runBlocking`のような望ましくない回避策を
  伴わない限り実装不可能だった。この修正のタイミングで是正する
  （`apap.context.ContextManager.build`/`DefaultContextManager.resolveMemoryInjection`も
  連鎖的にsuspend化、呼出元は全て既存のsuspendコンテキスト内のため実害はない）。
- 具体的な実装として`apap.runtime.ResilientQueryEmbedder`（デコレータ、実embedding呼出を行う
  delegateを受け取りCB/RateLimiterで包む）を用意する。`ExecutionEngineComposer`の
  `queryEmbedder`パラメータは、構築済みの`CircuitBreaker`/`RateLimiter`を受け取れる
  `queryEmbedderFactory: (CircuitBreaker, RateLimiter) -> QueryEmbedder`へ変更する
  （既定は`NoOpQueryEmbedder`のまま、ラップしても意味がないため既定では
  `ResilientQueryEmbedder`を挟まない）。

## 影響（Consequences）

- **担当フェーズ**: 実embedding呼出（`ResilientQueryEmbedder`の`delegate`が実際にProvider
  Adapterを呼ぶ実装）は引き続きP8以降とする。本ADRで実装したのはResilience機構を正しく
  経由させる**仕組み**（`ResilientQueryEmbedder`とcomposerのfactory化）であり、実際に
  Adapterを呼ぶdelegateの実装そのものではない。
- **制約**: 将来delegateを実装する際は`apap-context`へ`apap-provider`/`apap-adapter-spi`
  依存を追加しない（ADR-0022の制約を維持）。delegateは`apap-runtime`（またはそこから依存可能な
  モジュール）に置き、`ResilientQueryEmbedder`でラップして`queryEmbedderFactory`から返す。
- **見直す条件**: なし（本ADRはADR-0022の欠陥修正であり、新たな見直し条件は導入しない）。
- **関連**: [ADR-0012](ADR-0012-cache-hit-quota-ratelimit-handling.md)（RateLimiterスコープ判断の
  対比）、[ADR-0022](ADR-0022-query-embedder-resilience-boundary.md)（Superseded）、
  `apap.runtime.ResilientQueryEmbedder`、FR-CTX-004、02_システム仕様.md 2.17。
