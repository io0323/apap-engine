# ADR-0018: Fallback移行の予算充足判定にp90レイテンシを使う（設計書2.12からの意図的逸脱）

## ステータス

Accepted（2026-08-22）

## コンテキスト

`docs/design/02_システム仕様.md` 2.12は、Fallback移行条件を「予算残 > 次候補の最低所要見込み
（**p50**レイテンシ）」と定める。一方、実装（`apap.execution.fallback.FallbackEngine`）は
`Candidate.p90LatencyMs` を使っており、コードレビューでこの乖離が指摘された
（P6着手前レビュー、2026-08-22）。

乖離の原因を調査した結果、p50データは現在ドメインのどこにも存在しないことが判明した。
`apap.domain.model.vo.HealthLatencySnapshot`（2.5.2のS_latency/S_avail算出用）は
`p90LatencyMs`のみを保持し、`apap.domain.port.HealthLatencyStatsRepository`もp90のみを
記録・集計する契約になっている。`apap.domain.service.routing.Candidate`の`p90LatencyMs`は
この値をそのまま転記したものであり、p50を別途保持していない。

したがって「p50に戻す」ことは、実際には次の3点を横断する変更を要する（Fallback判定式を
書き換えるだけでは済まない）:

1. `HealthLatencySnapshot`へ`p50LatencyMs`（中央値）を追加し、その算出に必要な生データ
   （現状は集計値のみで、生の観測値のヒストグラム/サンプル列を保持していない）を
   保持するよう記録実装を変更する
2. `Candidate`へ`p50LatencyMs`を追加する（apap-domain）
3. `apap.routing.CandidateFactory`を新フィールドの設定に対応させる

(2)は`Candidate(...)`を直接構築している既存のテスト（`RoutingDomainServiceTest`、
`CandidateFactoryTest`、`RoutingEngineTest`、`RoutingHardFiltersTest`等、P2-P3で
マージ済み・レビュー済みのapap-routing/apap-domainテスト群）すべてに影響する、
本タスク（P6着手前修正）のスコープを大きく超える横断変更である。

## 決定

`FallbackEngine`のFallback移行判定は、設計書2.12の文言どおりの「p50」ではなく、
**`Candidate.p90LatencyMs`を意図的に使い続ける**。

## 影響（Consequences）

- **これは設計書2.12からの意図的な逸脱であり、Fallback試行回数が減る方向に働く**
  （p90は分布の裾に近い、より悲観的な値のため、「予算残 > 見込み」の判定がp50より
  不成立になりやすく、Fallback Engineがより早く諦める＝可用性がわずかに設計より
  低くなる。NFR-AVL-002「Fallbackにより機能継続」への寄与が設計意図よりやや弱い）。
- **制約**: `FallbackEngine`（`apap.execution.fallback`）のこの判断を「実装上の些末な選択」
  として扱わないこと。将来p50データが利用可能になった場合、本ADRをSupersedeして
  設計書どおりp50へ切り替えることを検討する。
- **見直す条件**: (a) `HealthLatencyStatsRepository`がp50（または分布ベースの見込み値）を
  提供するようになった場合、(b) Fallback試行が予算切れで打ち切られる頻度が実運用上
  問題になると判明した場合（p90を使うことでFallbackが本来可能だったはずの試行を
  早期に諦めているケースが有意に発生している場合）。
- **関連**: `docs/design/02_システム仕様.md` 2.12、2.5.2（p90ベースのS_latency算出との整合）、
  P6着手前レビュー（2026-08-22）。
