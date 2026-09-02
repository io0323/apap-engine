# ADR-0027: 13.1のエンドポイントのうち提供していないものを明示的に区別する（EndpointCatalog + NOT_IMPLEMENTED）

## ステータス

Accepted（2026-09-02）

## コンテキスト

`docs/design/13_API設計.md` 13.1は約40のエンドポイントを列挙している。P10でGateway
（`gateway/apap-gateway`）を実装するにあたり、これらのうち相当数が**現時点の`apap-runtime`に
対応するユースケースを持たない**ことが判明した。

`ApapEngine`が公開しているのは `execute` / `executeStream` / `capabilities` / `admin`
（`ApapAdmin`: Provider/Model/Alias/Policy）/ `health` のみである。したがって:

- **Batch**（4エンドポイント）: `BatchJob` Aggregateと`BatchJobRepository`は存在するが、
  それを駆動するManager/UseCaseと`ApapEngine`上の公開口が無い。
- **Memory**（4）/ **Session**（2）/ **Conversation**（4）: `MemoryManager`/`SessionManager`/
  `ConversationManager`は`apap-context`に存在するが、`ApapEngine`から到達できない。
- **Admin系の一部**: quotas / budgets / analytics / audit / plugins / caches:invalidate /
  credentials:rotate / models:discovered / Provider PATCH は`ApapAdmin`が公開していない
  （quotas/analyticsはP9で意図的にスコープ外とした——`ApapAdmin`のKDoc参照）。
- **`GET /v1/aliases`**: `AliasRepository`にテナント単位の一覧取得が無く（`findByName`のみ）、
  一覧を組み立てられない。
- **`GET /admin/v1/models`（無条件一覧）**: `ModelRepository`に`findAll`が無い。

本タスクの制約は「ビジネスロジックをGatewayに置かない」である。上記をGatewayで実装するには
Repositoryを直接叩くかユースケースをGatewayに書くことになり、いずれもこの制約に反する。

一方、本タスクは「未実装Capabilityに対応するエンドポイントは、実装済みかどうかを明示的に
区別すること（**黙って501を返さない**）」も要求している。

なお13.4のエラーコード表に`NOT_IMPLEMENTED`は存在しない。

## 決定

### 1. `EndpointCatalog`を単一の情報源として持つ

`apap.gateway.catalog.EndpointCatalog`に13.1の**全エンドポイントを1行も削らずに**列挙し、
各行へ`IMPLEMENTED` / `NOT_IMPLEMENTED`と、後者には「何が足りないから提供できないのか」を
必須の`unavailableReason`として持たせる（`EndpointSpec`の`init`で強制）。

表から行を消さないのは、消すと「そのエンドポイントは存在しなかったこと」になり、
黙って落とすのと同じ結果になるため。

### 2. 13.4に無い`NOT_IMPLEMENTED`(501)を1つだけ追加する

`apap.gateway.error.ApiError`は13.4の18コードを`apap.domain.model.vo.ErrorCode`から
そのまま使い（**Gateway側に13.4の表を複製しない**——複製するといずれ片方だけ更新されてズレる）、
Gatewayが追加するのは`NOT_IMPLEMENTED`のみとする。

応答は他と同じRFC 9457 Problem Detailsで、`detail`に`unavailableReason`をそのまま載せる。
これにより利用者は「まだ無い」と「壊れている」を区別でき、かつ何が必要かも分かる。

### 3. 提供状況を事前に取得できる口を設ける

`GET /v1/_endpoints`で`EndpointCatalog`を機械可読に返す。501を受け取って初めて分かる、
という状態を避けるため（13.1の表には無い追加エンドポイントであり、カタログ自身にも
IMPLEMENTEDとして載せている）。

### 4. 「Capabilityが未提供」と「エンドポイントが未実装」を混同しない

`/v1/images/generations`等のCapability系エンドポイントは**すべて実装する**。
`ApapEngine.execute`はCapability非依存であり、対応するProvider/Modelが登録されていない場合は
エンジンが13.4に沿って`CAPABILITY_NOT_AVAILABLE`(404) / `NO_CANDIDATE_AVAILABLE`(503)を返す。
これは正しい応答であって「未実装」ではない。501を返すのは「ユースケースがそもそも無い」場合だけ。

## 影響（Consequences）

- **制約**: 13.1のエンドポイントを追加・実装する際は`EndpointCatalog`と実ルートの両方を更新する。
  `EndpointCatalogTest`が「カタログとKtorに登録された実ルートの一致」を機械検証するため、
  片方だけの更新はテストが落ちる（CLAUDE.md不変条件9: 検査が実際に落ちることを確認済み）。
- **見直す条件**: `ApapEngine`/`ApapAdmin`にBatch/Memory/Session/Conversation/Quota等の
  ユースケースが追加された時点で、該当行を`IMPLEMENTED`へ移す。全行がIMPLEMENTEDになれば
  `NOT_IMPLEMENTED`コード自体を削除できる。
- **未決定のまま残る事項**: 上記ユースケースを`apap-runtime`のどのAPI形で公開するか
  （`ApapEngine`直下に生やすか、`admin`のような子ファサードを増やすか）はP11以降の判断。
- **関連**: CLAUDE.md不変条件9（シグナルの不在を成功と読まない）、ADR-0003（REST/SSEのみ）。
