# ADR-0020: Fallback移行の予算充足判定をp50レイテンシへ復帰する（ADR-0018のSupersede）

## ステータス

Accepted（2026-08-22）

## コンテキスト

ADR-0018は、設計書2.12が定める「予算残 > 次候補の最低所要見込み（**p50**レイテンシ）」を、
`Candidate.p90LatencyMs`を意図的に使い続けるという判断で置き換えた。その根拠として記録した
のは「p50データが現在ドメインのどこにも存在せず、`HealthLatencySnapshot`/`Candidate`/
`CandidateFactory`と複数のマージ済みテストにまたがる横断変更を要する」という**実装コスト**
だった。

この判断は着手前レビュー（2026-08-22）で差し戻された。理由は次の通り:

1. **実装コストは設計逸脱の正当化にならない**。CLAUDE.md不変条件8のADR化基準は「設計書の
   記述が不明確・曖昧で解釈が必要な場合」を想定しており、設計書の記述が明確（2.12は
   「p50」と明記）な場合に実装側の都合で逸脱する判断は同列に扱えない。「既存実装が仕様より
   優先される」前例を作ることは避ける。
2. **p90への変更はFallbackの意味論を変えてしまう**。2.12の「最低所要見込み」は、
   「そもそも試す価値があるか」に答える楽観的な下限値として設計されている。p90（分布の
   裾に近い悲観値）を使うと「ほぼ確実に間に合うか」という別の問いに変わり、Fallback試行を
   構造的に抑制する方向に働く。
3. **失敗コストは非対称かつ小さい**。試行を諦めれば`PROVIDER_UNAVAILABLE`系のエラーが
   確定するのに対し、試して予算切れになっても起きるのは`TIMEOUT`だけであり、呼び出し側
   から見た結果の質（失敗）は同じで診断情報の粒度が異なるだけである。一方、成功確率は
   p50判定で約50%、p90判定でほぼ0%と大きく異なる。Fallbackは可用性低下時にこそ価値を
   発揮する機構であり、低確率でも試す価値がある場面を対象にしている。
4. **実装コストの見積もり自体が過大だった**。`InMemoryHealthLatencyStatsRepository`
   （`apap-testkit`）は既にウィンドウ内の生の観測値（`Outcome`のリスト）を保持しており、
   p90を`nearest-rank`法で算出済みだった。p50の追加は同じ算出関数にもう1つの分位点を
   通すだけで済み、ADR-0018が想定したほどの規模の変更ではなかった。また本番未リリース
   （外部消費者なし）のため「マージ済み」であること自体は制約として重みを持たない。

## 決定

`FallbackEngine`のFallback移行判定を、設計書2.12の記述どおり**`Candidate.p50LatencyMs`**
（次候補のp50レイテンシ）へ戻す。ADR-0018はこの決定によりSupersededとする（ADR-0018の
本文は改変せず、ステータス行のみ`Superseded by ADR-0020`に変更した）。

これに伴い以下を変更した:

- `apap.domain.model.vo.HealthLatencySnapshot`に`p50LatencyMs: Long`を追加（`p90LatencyMs`
  は2.5.2のS_latency算出に引き続き使うため維持）
- `apap.testkit.inmemory.InMemoryHealthLatencyStatsRepository`がp50をp90と同じ
  nearest-rank法で算出するよう変更
- `apap.domain.service.routing.Candidate`に`p50LatencyMs: Double`を追加
- `apap.routing.CandidateFactory`が`HealthLatencySnapshot.p50LatencyMs`を`Candidate`へ転記
- `apap.execution.fallback.FallbackEngine`の予算充足判定を`p90LatencyMs`から
  `p50LatencyMs`に変更

## 影響（Consequences）

- Fallback移行判定が設計書2.12と一致する。p50はp90より小さい値のため、同じ残予算に対して
  Fallback試行が許可されやすくなる（NFR-AVL-002「Fallbackにより機能継続」への寄与が
  ADR-0018時点より強まる）。
- 一方で、p50はあくまで中央値であり、実際の所要時間がp50を超えて予算を使い切る試行が
  ADR-0018時点より増える可能性がある（設計上想定済みのトレードオフであり、2.12の
  「最低所要**見込み**」という表現自体が、外れる場合があることを前提にしている）。
- `Candidate.p90LatencyMs`はS_latency/S_avail算出（2.5.2）専用として残る。p50とp90は
  用途が異なる別フィールドであり、将来どちらかのみを削除する変更は要件ID
  （NFR-AVL-002 / 2.5.2のスコアリング）への影響を確認してから行うこと。
- **本ADRを再度Supersedeする条件**: p90を使う判断に戻す場合は、実装コスト以外の設計上の
  論拠（例: 予算超過した試行でもProvider側で課金が発生するため、成功見込みの低い試行を
  避ける必要がある、など）を示し、コスト影響の見積もりを添えること。実装コストのみを
  理由とする逸脱は認めない。
- **関連**: `docs/design/02_システム仕様.md` 2.12、2.5.2、ADR-0018（Superseded）、
  着手前レビュー（2026-08-22）。
