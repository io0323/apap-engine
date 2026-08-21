# ADR-0017: apap-runtime埋込時のJacksonバージョン整合と、プロジェクト全体でのJSONスタック一本化

## ステータス

Accepted（2026-08-21）

## コンテキスト

ADR-0016で導入した`com.networknt:json-schema-validator:1.5.9`（`modules/apap-provider`の
`CapabilityRegistry`が使用）はJackson 2系（`jackson-databind:2.18.3`を推移的に要求）に依存する。
`modules/apap-runtime`（`build.gradle.kts`）は既に`modules:apap-provider`を`implementation`で
依存しており、`modules/apap-runtime`は「埋込用ライブラリ。別プロジェクト`prompt-engine`が依存する」
（CLAUDE.md）という性質上、Jacksonは既に埋込成果物の依存木に入っている。

JVMライブラリ埋込において宿主側と埋込側のJacksonバージョン差異は最頻出の実行時障害
（`NoSuchMethodError` / `NoClassDefFoundError`）である。この種の不整合はコンパイル時には
検出されず、宿主側（prompt-engine）との実結線（P9で予定）まで表面化しない。

宿主側の実態を`/Users/io/projects/GitHub/engine/prompt-engine`（本リポジトリと同一マシン上の
別プロジェクト）で直接確認した:

- `prompt-engine/gradle/libs.versions.toml`: `jackson = "2.22.1"`、
  `jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }`。
  Jacksonは既にprompt-engine自身の標準JSONスタックであり、`plugins/formatter-json`・
  `modules/prompt-engine-bootstrap`・`modules/prompt-engine-interface`・
  `modules/prompt-engine-infrastructure`・`tests/integration`が利用している。
- `kotlinx-serialization`・Gson・`org.json`・Moshi等、他のJSONライブラリは一切宣言されていない。
- prompt-engineは`org.springframework.boot:spring-boot-dependencies`を`implementation(platform(...))`
  （Gradleネイティブの`platform()`、`enforcedPlatform()`ではない）でのみ適用しており、
  Jacksonバージョンをハード強制（`resolutionStrategy.force`や`enforcedPlatform`）してはいない。
  すなわちGradleの既定解決戦略（同一classpath上で要求された最高バージョンを採用）に従う。
- prompt-engine側からapap-runtime（またはapap-engineの他モジュール）への依存宣言は現時点で
  存在しない（P9未着手、README上のマイルストーンとしてのみ記載）。

Jacksonプロジェクト自身が「同一メジャーバージョン内（2.x同士）はパッチ・マイナー間で
後方/前方互換」を明示的な方針として維持しているため、今回の懸念の実質的なリスクは
「Jackson 2.x内のマイナーバージョン差異」ではなく「メジャーバージョンの分裂（2.x対3.x）」
「`strictly()`等によるハード強制の衝突」である。ADR-0016策定時に、networknt/json-schema-validatorの
2.x/3.x系列が確認時点で公開後1日未満のJackson 3ベース書き換えだったためこれを避け、
実績のあるJackson 2ベースの1.5.9系列を選定済みであり、メジャーバージョンの分裂は既に回避している。

## 決定

### 0-a. Jacksonバージョンの明示的な整合（案B: 明示宣言 + 記録）

**Jacksonのshade/relocate（案A）は現時点では採用しない。** 理由:

1. Shadeを適用すべき境界は個々の内部モジュール（`apap-provider`）ではなく、公開成果物である
   `apap-runtime`のパッケージング工程である。しかし`apap-runtime`は現時点で`ApapEngine`/
   `ApapEngineBuilder`本体の実装を持たない配線先行の空モジュールであり、公開アーティファクトの
   パッケージング方式（fat jar化するか、通常のGradle Module Metadataで推移的依存として配布するか）
   自体が未確定。この状態でShadow Plugin等のビルド構成を先行導入すると、実際のパッケージング方針が
   固まった際に作り直しが必要になる可能性が高い（CLAUDE.md「将来の要求を見越した設計をしない」）。
2. 上記のとおり、実際の宿主（prompt-engine）は同一メジャーライン（Jackson 2.x）を使用し、
   ハード強制もしていない。Jacksonの2.x内互換方針とGradleの標準解決戦略により、
   多くの場合Gradleが両者の要求のうち高い方へ自動的に収束する。Shadeが本来解決する
   「メジャーバージョン分裂」「宿主が全く異なるJSONライブラリを使う」ケースには現時点で該当しない。
3. 案Aを不要と判断するのではなく**保留**する: 実際の結合（P9）で問題が顕在化した場合、
   または`apap-runtime`のパッケージング方針が固まった時点で、本ADRを`Superseded`とし
   Shade導入のADRを起票する。

**代わりに次を実施する（案B）**:

1. `modules/apap-provider/build.gradle.kts`に`jackson-databind`へのGradle依存制約
   （`constraints { }`、直接の実装依存としては追加しない——`apap-provider`のソースコードは
   Jackson APIを直接呼ばず、`json-schema-validator`が推移的に必要とするだけのため）を追加し、
   バージョンをprompt-engineの実宣言値`2.22.1`へ明示的に揃える。これによりGradleの解決結果が
   `1.5.9`が推移的に要求する`2.18.3`ではなく`2.22.1`になり、apap-engine自身のビルド・テストが
   実際の宿主が使うであろうバージョンに対して行われる（"dogfooding"）。
2. `gradle/libs.versions.toml`にこの決定の根拠をコメントとして残す。
3. 本ADRを、P9で作成予定の`docs/integration/prompt-engine.md`に転記すべき制約として明記する
   （本ADRの「未決定のまま残る事項」参照）。
4. 宿主バージョン差異下での結合テストは、`apap-runtime`↔`prompt-engine`の実結線がまだ存在しない
   （P9未着手）ため、意味のある形で今書くことができない。フェイクの結合テストを今でっち上げる
   より、P9側での既知の検証項目として明記することを優先する。

### 0-b. プロジェクト全体のJSONスタックをJacksonに一本化する

apap-engineのコア（`apap-domain`等）はどのJSONライブラリにも依存しない
（Vendor Neutral・依存ゼロ原則により、そもそもドメイン層でJSONを直接扱わない）。
JSONライブラリが必要になるのは`apap-adapter-spi`実装（Provider固有のHTTPペイロード、
各Adapterが個別に選べる）と、`apap-provider`のCapability Registry（JSON Schema検証）のような
Application層寄りの箇所に限られる。

後者について: **Jacksonをプロジェクト全体（apap-engine）のJSONスタックとして選定する。**
理由は本ADRの発端が示すとおり、選択の余地なくJacksonが埋込先の依存木に既に入っており、
かつ宿主（prompt-engine）自身の標準スタックとも一致するため、`kotlinx-serialization`等の
別スタックを追加することは依存の重複（同種の機能を持つ2つのライブラリを埋込先に共存させる）を
招くだけで得るものがない。

- `kotlinx-serialization`・Gson・`org.json`・Moshi等、Jackson以外のJSON関連ライブラリを
  `apap-domain`/`apap-adapter-spi`/`apap-provider`/`apap-runtime`（およびこれらが依存する
  他モジュール）へ追加することを禁止する。真に必要になった場合は本ADRを`Superseded`とする
  新規ADRを起票し、Jacksonからの移行または共存の是非を判断すること。
- `gradle/libs.versions.toml`のJacksonエントリにこの決定への参照コメントを付す。

## 影響（Consequences）

- **制約**: `apap-provider`（および将来Jacksonを必要とする他モジュール）は、Jacksonのバージョンを
  個別に選ばず、`libs.versions.toml`の`jackson`バージョンエントリを参照すること。このバージョンは
  prompt-engine側の値と手動で同期させる（自動追従の仕組みはない。ズレに気づく手段は現状
  レビュー時の目視のみ——将来的にはP9で実結線した際のGradle依存グラフ検証で機械的に検知できる）。
- **見直す条件**: (1) P9でprompt-engineとの実結線を行った際にバージョン不整合による実行時エラーが
  実際に発生した場合、(2) `apap-runtime`の公開パッケージング方式（fat jar化等）が確定した場合、
  (3) prompt-engine側がJackson以外のJSONスタックへ移行した場合。いずれかに該当したら本ADRを
  `Superseded`とし、Shade導入（案A）または別方針を新規ADRとして起票する。
- **未決定のまま残る事項**: 実際の宿主バージョンとの整合を検証する結合テストはP9側の実装課題として
  残る。本ADRの内容（Jacksonバージョン整合方針、JSONスタック一本化の決定）は、P9で
  `docs/integration/prompt-engine.md`を作成する際に転記・参照すること。
- **関連**: ADR-0016（`json-schema-validator`選定の経緯）、CLAUDE.md不変条件6
  （DI/フレームワーク非依存——Jacksonはこの不変条件が対象とするDIコンテナ・アプリフレームワーク・
  ロギング実装のいずれにも該当しないため抵触しない）。
