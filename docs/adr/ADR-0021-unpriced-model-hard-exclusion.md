# ADR-0021: 単価未登録Modelはルーティング候補から除外する（ハードフィルタ方式）

## ステータス

Accepted（2026-08-26）

## コンテキスト

P7で `apap.routing.CostEstimator` の既定実装を `ZeroCostEstimator`（常にゼロ、FR-RTE-002未実効）から
`RealCostEstimator`（`PriceBookRepository` から実単価を引く）へ置き換えた際、単価未登録のModelに
対する当初実装は「十分に高いペナルティ値を返し、`RoutingDomainService.computeScores` の
min-max正規化でS_costを最劣後にする」という**除外ではなくスコアで不利にする**方式を採っていた。

しかしこの方式には次の欠陥がある。

- ペナルティは「他候補より不利にする」だけであり、**選択され得る**（唯一の候補である場合、
  `optimize_for=latency/quality` 等コスト以外の軸が優越する場合）。
- 選択された場合、後段の `apap.cost.DefaultCostEngine.estimate()`（`ExecutionEngine.executeClaimed`
  がRouting直後に呼ぶ）が同じ理由で `PriceEntryNotFoundException` を送出する。この例外は
  `ExecutionEngine` のどこにも捕捉されず未処理のまま伝播し、利用側には整形されないまま失敗が
  返る。ルーティングが「選べてしまう」せいで、後段の一貫した失敗を毎回引き起こすだけの
  無駄な処理（Routing自体、Quota予約直前までの処理）が発生する。
- `docs/design/15_Provider追加手順.md` 15.2 はModel登録手順の一部として単価登録を必須の
  ステップと明記しており、「登録されていれば選ばれてよい」ではなく「登録されていなければ
  選ばれるべきでない」がそもそもの設計意図である。

この解釈の違いは実質的な要件充足性に影響する: ペナルティ方式のままだと、単価未登録Modelが
選択され得る限り、FR-OBS-005（Budget/Costの正確な計上）とNFR-DAT系（コスト集計の正しさ）が
「まれに、静かに、原因不明のまま」満たされなくなるケースを構造的に排除できない。

## 決定

単価未登録のModelを、Candidate生成の時点で除外する（ハードフィルタ方式、案A）。

- `apap.routing.CostEstimator.estimate()` の戻り値を `Money` から `Money?` へ変更する。
  `null` は「有効な `PriceEntry` が存在しない」ことを表す。
- `RealCostEstimator.estimate()` は `PriceBookRepository.findCurrentEntry` が `null` を返した場合、
  ペナルティ値ではなく `null` を返す。
- `apap.routing.CandidateFactory.toCandidate()` は `costEstimator.estimate(...)` が `null` の場合、
  `Candidate` を組み立てずスキップする（`build()` の `mapNotNull` で自然に除外される）。
  02_システム仕様.md 2.5.2のハードフィルタ a〜g（`RoutingHardFilters`, 順序固定で明示列挙）には
  含めない。02_システム仕様.md 2.5.2は「Model+Provider+Health+Priceから候補を組み立てる」
  `CandidateFactory` 自体のKDoc（03_基本設計.md 3.6）が既に「Price」をCandidate解決の一部として
  扱っており、a〜gの列挙対象（Candidateが存在することを前提とした事後フィルタ）とは異なる、
  より早い段階（Candidateがそもそも存在するか）の判定として位置づける。
- `ZeroCostEstimator`（差替可能なStrategyとして存置）は本ADRの対象外。全モデルを「ゼロ円」として
  扱う既存の意味を変えない（`estimate()` は非nullのまま `Money.zero(currency)` を返し続ける）。

## 影響（Consequences）

- **可用性への影響**: 新規登録直後でまだ単価が設定されていないModelは、単価登録が完了するまで
  一切のCapabilityで選択されない。これは意図した挙動（設計書15.2の「単価登録必須」の直接的な
  帰結）であり、後退ではない。
- **除外の可視性**: `CandidateFactory` は除外時にWARNログを出す（SLF4J、CLAUDE.md不変条件6で許可）。
  イベント発火は行わない（そもそも「選択されなかった」事実に対して発火すべき対象がない。
  選択された後の検知が必要になる案Bとは異なる）。
- **`DefaultCostEngine.estimate()`/`calculate()` の `PriceEntryNotFoundException`**: 本ADRにより
  通常の実行フローでは到達しなくなるが、Repository層の直接的な不整合（Routing完了後にPriceBookが
  変更される競合等）に対する最終防御として保持する。
- **見直す条件**: 将来、単価未確定のまま試験的にModelを提供したい運用要求が生じた場合は、
  「試験提供中はコストを見積り不能として明示応答する」設計（案B相当）を別途ADR化して追加する。
  本ADRを撤回する場合はステータスを `Superseded by ADR-YYYY` とする。
- **関連**: [ADR-0001](ADR-0001-datastore-selection.md)、`docs/design/15_Provider追加手順.md` 15.2、
  FR-OBS-005 / FR-RTE-002（`docs/traceability/requirements-matrix.md`）。
