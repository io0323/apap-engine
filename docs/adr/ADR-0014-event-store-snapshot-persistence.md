# ADR-0014: EventStoreRepositoryはスナップショット永続化とバージョン照会の両方を提供する

## ステータス

Accepted（2026-08-20）

## コンテキスト

`docs/design/03_基本設計.md` 3.4 は `EventStoreRepository` のメソッドを
`append(streamId, events, expectedVersion) / read(streamId, fromVersion) / snapshot` と記載するのみで、
`snapshot` の具体的なシグネチャ（引数・返り値）を定義していない（表記が曖昧）。

`modules/apap-domain` 実装時、この曖昧さを「楽観ロック用のバージョン照会」と解釈し、
`snapshot` を `latestVersion(streamId): Long` として実装した。これはレビューで指摘の通り誤りだった。

`docs/design/01_要件定義.md` NFR-DAT-003は「イベントストアは追記専用、スナップショットにより
再構築時間を制御」と明示的に要求している。Event Sourcing文脈での「スナップショット」は、
Aggregateの状態を定期的に保存し、再構築時に全イベントを再生しなくて済むようにする機構であり、
楽観ロック用のバージョン照会（`latestVersion`）とは別の関心事である。前者を後者に読み替えたことで、
NFR-DAT-003が要求する再構築時間の制御という機能そのものが実装から欠落していた。

4.5により、Event Sourcing対象（Provider / Model / Alias / Policy / BatchJob）は
「各Repositoryが再構築を担当」する。スナップショット機構がない場合、これらのAggregateが
長期間・多数のイベントを蓄積すると、`findById`のたびに`stream_id`の全イベントを起点から
再生することになり、再構築時間が青天井になる（Providerのcredential rotation履歴、
Policyのバージョン履歴など、長寿命Aggregateほど影響が大きい）。

## 決定

`EventStoreRepository` に以下を両方持たせる。

1. `latestVersion(streamId): Long` — 楽観ロック判定用の軽量なバージョン照会（変更なし）。
2. `saveSnapshot(snapshot: AggregateSnapshot<T>)` / `loadSnapshot(streamId, stateType): AggregateSnapshot<T>?`
   — NFR-DAT-003が要求するスナップショット機構本体。

`AggregateSnapshot<T>(streamId, version, state: T)` はスナップショット対象時点のAggregate状態
そのもの（`T`は例えば`apap.domain.model.provider.Provider`）を保持する。Domain層はシリアライズ方式を
規定せず、実際の永続化・(de)serializeはInfrastructure層の実装に委ねる。

各Aggregate用Repository（Infrastructure層実装）は、`loadSnapshot`で最新スナップショットを取得し、
`read(streamId, fromVersion = snapshot.version + 1)`でスナップショット以降のイベントのみを読み再生する
ことで、再構築時間を「スナップショット以降のイベント数」に制限する。スナップショット取得の
タイミング（何イベントごとに取るか等）はInfrastructure層のポリシーとし、本ADRでは規定しない。

## 影響（Consequences）

- **制約**: Event Sourcing対象Aggregateの各Repository実装（Infrastructure層、本セッションでは未着手）は、
  `findById`等の再構築処理で必ず`loadSnapshot`を先に試行し、存在すればそこから、存在しなければ
  `fromVersion=0`から再生する実装とすること。スナップショットを一切使わない実装は本ADRの決定に反する。
- **見直す条件**: なし。NFR-DAT-003の記述漏れではなく、3.4の曖昧な表記を実装時に誤って解釈した
  ことの是正であり、将来の要件変更で見直す性質のものではない。
- **未決定**: スナップショット取得の周期・トリガー（イベント件数ベース／時間ベース等）は
  Infrastructure層の実装判断とし、本ADRでは決定しない。
- **関連**: `docs/design/03_基本設計.md` 3.4、`docs/design/01_要件定義.md` NFR-DAT-003。
