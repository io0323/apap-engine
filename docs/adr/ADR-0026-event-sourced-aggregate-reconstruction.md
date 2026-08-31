# ADR-0026: Event Sourcing対象Aggregateの再構築方式（apply/fold構造、イベントpayload拡張、スナップショット取得ポリシー）

## ステータス

Accepted（2026-08-30）

## コンテキスト

`docs/design/04_ドメイン設計.md` 4.1/4.5により、Provider / Model / ModelAlias / RoutingPolicy /
BatchJobの5 AggregateはEvent Sourcing対象と定義され、「各Repositoryが再構築を担当する」とされている。
ADR-0014は`EventStoreRepository.saveSnapshot/loadSnapshot`を実装したが、その先——実際にイベント列
（またはスナップショット＋差分イベント）から現在状態を復元するロジック自体——は「Infrastructure実装時に
決定する」として保留されていた（ADR-0014 Consequences「未決定」）。本ADRはその保留事項を解消する。

保留事項を実装する過程で、次の2点が判明した。いずれも3.4/4.5の「再構築」という言葉が具体的に
何を要求するかが曖昧だったことに起因し、解釈を誤るとNFR-DAT-003（イベントストアは追記専用、
スナップショットにより再構築時間を制御）が要求する「正しい状態への再構築」自体が成立しなくなる
（ADR-0014が是正したケースと同種の問題）ため、ADR化する。

1. **コマンド決定ロジックとの関係**: 5 Aggregateとも、コマンド処理は`ProviderManager`/`ModelManager`
   （RoutingPolicy/BatchJobには対応するManager自体が無い）が命令的に`Aggregate.transitionTo()`等を
   呼んで状態を直接書き換える構造であり、`decide(state, command): events[]`のような、コマンドから
   イベント列を導出する純粋関数層は存在しない。本ADRはこの構造に手を入れない
   （既存Managerの責務はそのまま、再構築専用の`apply`のみを追加する）。
2. **既存イベントpayloadの不足**: 14_イベント一覧.mdはイベント名・発火元・購読先・用途を規定するのみで、
   payloadの網羅性までは規定していない。実装済みの多くのイベントは「通知」目的（Audit記録・Read Model
   更新のトリガー）で設計されており、Aggregateの全フィールドを運ばない。例:
   `ProviderRegistered`はproviderId/name/adapterPluginIdのみでendpoints/credentialRefs/rateLimits等を
   持たない。`PolicyUpdated(policyId, scope)`はrules/version/statusを持たない。
   `BatchJobSubmitted`はtenantId/itemsを持たない。`CredentialRotated`は新CredentialRefの実体
   （secretRef）を持たない。`ModelRegistered.capabilities`は`CapabilityId`の裸のリストで
   `ModelCapability.constraints`を失う。`AliasChanged`はAlias名(`name`)を持たない。
   また、一部の状態遷移（Provider REGISTERED→VALIDATING、BatchJob SUBMITTED→QUEUED等）には
   そもそも14章に対応するイベントが存在しない（`ProviderManager.beginValidation`のコード内コメント
   「14章に対応するイベントが無いため発行しない」が示す通り、既存実装が既にこの前提で書かれている）。

## 決定

### 1. `apply(state, event): state`によるfold構造を採用する（`decide`層は追加しない）

`apap.domain.model.reconstruct(events, initial, apply)`という汎用foldヘルパー1つ
（`events.fold(initial, apply)`）を`apap-domain`に置き、各Aggregateごとに専用の
`fun applyXxxEvent(state: T?, event: DomainEvent): T`を対応するAggregateのパッケージに定義する
（`apap.domain.model.provider.applyProviderEvent`/`modelcatalog.applyModelEvent`/
`modelcatalog.applyModelAliasEvent`（ModelAliasは別ファイル）/`routing.applyRoutingPolicyEvent`/
`execution.applyBatchJobEvent`）。関数名を単に`apply`とせず対象Aggregate名を含めたのは、
ModelとModelAliasが同一パッケージ（`apap.domain.model.modelcatalog`）にあり、
`::apply`という関数参照がオーバーロード解決不能になるため（要件充足に影響しない実装判断の
ためADR化基準上は本文に含めなくてもよいが、5つ全てに同じ命名規則を適用する一貫性のため
ここに記す）。

各Repositoryの`findById`等は、`EventStoreRepository.loadSnapshot`で得たスナップショットを初期値、
`EventStoreRepository.read(streamId, fromVersion = snapshot.version + 1)`で得た差分イベント列を
`reconstruct`に渡すことで現在状態を得る（スナップショットが無ければ`initial=null`、
`fromVersion=0`から全件）。

想定外のイベント型に遭遇した場合は`state`をそのまま返さず`UnexpectedEventForAggregateException`
（`apap.domain.model`）を投げる。9_状態遷移図.mdの「不正遷移は専用例外を投げる」という規約を、
再構築処理にも同様に適用したものである。

**再構築時は`Aggregate.transitionTo()`等の遷移合法性チェックを経由せず、`copy()`で直接
フィールドを設定する。** 理由は次項の「意図的に再構築されない遷移的状態」で述べる通り、
再構築中に辿る状態列はライブのコマンド処理が辿った状態列と一致しない（一部の遷移的状態を
スキップする）ため、`transitionTo()`の遷移表チェックにかけると正当に失敗しうる。遷移の合法性は
コマンド決定時（ライブパス、既存Manager内）で既に検証済みという前提に立ち、再構築では
再検証しない。

### 2. 既存イベントのpayloadを拡張する（イベント名は一切変えない）

`DomainEventCoverageTest`が14章の50イベント名と実装の完全一致（双方向）を機械検証しており、
新規イベント型の追加は許されない。また`docs/design/*.md`は編集しない（CLAUDE.md不変条件8）。
そのためイベント**名**は変えず、再構築に必要なフィールドのみを既存イベントクラスに追加した。

| イベント | 追加したフィールド |
|---|---|
| `ProviderRegistered` | `spiVersion, endpoints, authType, credentialRefs, rateLimits, priority, regions, tags` |
| `ProviderValidated` | `credentialVersion`（ACTIVEへ昇格したCredentialRefを`CredentialRef.version`で特定） |
| `CredentialRotated` | `newSecretRef` |
| `ModelRegistered` | `capabilities`の型を`List<CapabilityId>`→`List<ModelCapability>`に変更（`constraints`を保持するため）、`modelName, version, contextWindow, maxOutputTokens, regions, priority`を追加 |
| `AliasChanged` | `name` |
| `PolicyUpdated` | `tenantId, workflowId, rules, version, status` |
| `BatchJobSubmitted` | `tenantId, items` |

これらのイベントはいずれも本タスク以前は実際に生成されていなかった（`ModelManager`は
`eventPublisher.publish()`のみでEvent Storeへ永続化しておらず、`CredentialRotated`/`PolicyUpdated`/
`BatchJobSubmitted`はどのManagerからも呼ばれていなかった）ため、payload拡張によって既存の
稼働中コードへ影響はない。

### 3. 意図的に再構築されない遷移的状態

14章に対応イベントが無い遷移（Provider REGISTERED→VALIDATING、VALIDATING→REGISTERED
（Credential起因でない検証失敗）、BatchJob SUBMITTED→QUEUED）は、再構築の対象外とする。
再構築後の状態は、これらの遷移的状態を経由せず直前/直後の安定状態（それぞれREGISTERED、
RUNNING）に留まる。プロセスクラッシュ等でこれらの遷移的状態のまま失われた場合、再構築後は
再検証・再キューが必要になる——これは許容する（VALIDATING/QUEUEDはいずれも「これから
再試行されるべき」一時状態であり、直前の安定状態に戻ることは安全側に倒れる）。

### 4. スナップショット取得ポリシー

イベント件数ベース、既定100件ごと。`Jdbc*Repository`のコンストラクタに
`snapshotEveryNEvents: Int = 100`として持たせ、設定可能にする（`saveEvents`実行後、直近の
スナップショットからのイベント数がこの値を超えていたら`saveSnapshot`も呼ぶ）。
NFR-DAT-003が求める再構築時間の制御と、書き込みのたびにスナップショットを取るコストの
バランスを取った値として採用する。

## 影響（Consequences）

- **制約**: 新規イベントpayloadフィールドを追加する際は、必ずこのADRの表を更新し、
  再構築（`apply`）が要求する情報を過不足なく運ぶことを確認すること。
- **見直す条件**: Provider/BatchJobの遷移的状態（VALIDATING/QUEUED）を永続的に追跡する要件が
  将来追加された場合、対応イベント（例: `ProviderValidationStarted`）の新設が必要になり、
  14_イベント一覧.mdの改版が前提となる。現時点ではその要件はない。
- **関連**: ADR-0014（EventStoreRepositoryのスナップショット機構）。
