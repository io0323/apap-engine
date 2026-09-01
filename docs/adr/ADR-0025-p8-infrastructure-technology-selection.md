# ADR-0025: P8 apap-infrastructure/apap-plugin実装の技術選定（JDBC/分散KVS/Plugin署名検証）

## ステータス

Accepted（2026-08-29）

## コンテキスト

ADR-0001（データストア技術選定）は「具体的なRDBMS製品・分散KVS製品は本ADRでは選定しない。
デプロイ環境の決定に委ねる」とし、ADR-0002（Secret Store責務分界）も「具体的な製品選定は
デプロイ時の決定であり、設計・実装をブロックしない」としている。しかし`modules/apap-infrastructure`
（JDBC Repository実装、分散CircuitBreakerStateStore/RateLimitCounterStore/CacheStore実装）と
`modules/apap-plugin`（Plugin署名検証）を実際に動くコードとして書くには、具体的な技術を選ぶ必要がある。

`docs/design/15_Provider追加手順.md` 15.1の`plugin.yaml`例も`signature: <配布パッケージ署名>`と
値の存在のみを規定し、署名アルゴリズムは指定していない。

ADR-0017（Jacksonバージョン整合）と同じ方針で、憶測で選ばず**実際の埋込ホストを確認**した。
`/Users/io/projects/GitHub/engine/prompt-engine`（本リポジトリと同一マシン上の別プロジェクト、
apap-runtimeを実際に依存する予定のホスト）の実装を確認したところ:

- `modules/prompt-engine-infrastructure/build.gradle.kts`が`flyway-core`/`flyway-postgresql`/
  `postgresql`（runtimeOnly）を使用し、`src/main/resources/db/migration/`にFlyway形式のマイグレーション
  （`V10__..sql`〜`V19__..sql`）を持つ。JDBC自体は`spring-boot-starter-data-jdbc`（Spring Data JDBC）
  経由だが、これはprompt-engine自身がSpring Bootアプリケーションだからであり、CLAUDE.md不変条件6
  （apap-runtime/依存モジュールにアプリフレームワークを持ち込まない）によりapap-infrastructureは
  Spring Data JDBCを使えない。
- prompt-engineは既にRedisクライアントとして`lettuce-core`を使用している
  （`RedisPromptCache`、ADR-0033決定d）。

## 決定

### 1. JDBC Repository実装

- 対象RDBMS: **PostgreSQL**（prompt-engine側の実宣言に合わせる）。
- マイグレーションツール: **Flyway**（`flyway-core`をapap-infrastructureに`implementation`追加）。
- JDBCアクセス方式: **素のJDBC**（`java.sql.DataSource`/`Connection`/`PreparedStatement`）。
  Spring Data JDBCは使わない（CLAUDE.md不変条件6違反のため）。この点はprompt-engine側の実装方式
  そのままではなく、apap-infrastructureの制約に合わせて意図的に逸脱する。

### 2. 分散KVS実装（CircuitBreakerStateStore / RateLimitCounterStore / CacheStore）

**Lettuce**（`lettuce-core`）を使う。prompt-engineが同じ技術を既に採用しており、Lettuceは
DIコンテナ・アプリフレームワークを持ち込まない素のクライアントライブラリのため、CLAUDE.md不変条件6
（SLF4J API/OpenTelemetry APIまでは可、という基準と同種の「素のクライアント/APIライブラリ」）に
抵触しない。

いずれも`modules/apap-infrastructure`にのみ`implementation`で追加し、`apap-domain`/`apap-runtime`の
コアには波及させない（Portを介した差替可能性はADR-0001の決定どおり維持する）。

### 3. Plugin署名検証

`java.security.Signature`（RSA/ECDSA、JDK標準API）による自前実装とする。新規外部依存を追加しない。
`PluginManifestParser`が独自YAMLパーサを自前実装した際の判断基準（フル機能ライブラリを持ち込まず
既知の狭いスキーマだけを扱う最小実装にする）と同じ精神。検証対象は`plugin.yaml`の`signature`
フィールド（Base64エンコードされた署名値）と、設定可能な信頼公開鍵（PEM等）。

具体的な署名アルゴリズム・鍵形式の選定はFR/NFRの充足可否を左右しない実装詳細のため、
CLAUDE.md「ADR化するか否かの判断基準」によりこれ以上のADR細分化はせず、該当実装クラスのKDocに
根拠を記す。

### 4. モジュール分割: JDBC/分散KVS実装をapap-runtimeの必須依存から外す

`modules/apap-runtime/build.gradle.kts`は既存の`modules/apap-infrastructure`（EventBus実装の置き場）
へ`implementation`依存している。`apap-runtime`は埋込用ファサードであり、`prompt-engine`をはじめとする
全ての埋込先が`apap-runtime`を依存するだけでFlyway/PostgreSQL JDBCドライバ/Lettuceを
無条件にtransitive依存することになってしまう。ADR-0001が「単一プロセス・埋込利用ではIn-Memory実装
（既定）で十分、分散KVS/JDBC実装はマルチノード運用時に切替える」としている決定と矛盾する。

上記1〜2で選定したJDBC/分散KVS実装は、既存の`modules/apap-infrastructure`には置かず、
新規モジュール`modules/apap-infrastructure-jdbc`・`modules/apap-infrastructure-distributed`
に分離する。`apap-runtime`はこの2モジュールに一切依存しない。既存の`modules/apap-infrastructure`
（EventBus、および本ADRで新規追加するIn-Memory Repository/SecretStore実装）は重量級の外部依存を
持たないため、`apap-runtime`の既存依存はそのまま維持する。

分散/JDBC実装を使いたい埋込ホストは、自身のビルドで`apap-infrastructure-jdbc`/
`apap-infrastructure-distributed`を明示的に依存追加し、そこで構築した具体的な実装インスタンスを
`ExecutionEngineComposer`等の既存のPort差替コンストラクタ引数（`cacheStore`・`cbStore`等、
既にIn-Memory既定値を持つ設計になっている）へ渡す。コア側のコード変更は不要。

## 影響（Consequences）

- **制約**: JDBC実装はPostgreSQL固有のSQL構文（`JSON`型、`ON CONFLICT`等）を使ってよいが、
  Portインターフェース（`apap-domain`）自体はPostgreSQL固有の型を露出しない。
  `apap-infrastructure-jdbc`/`apap-infrastructure-distributed`に`apap-runtime`から
  `implementation`依存を追加してはならない（上記4参照）。新規に重量級の外部ライブラリ依存を
  追加する実装は、既存の`apap-infrastructure`ではなく用途に応じてこの2モジュールいずれかへ置くこと。
- **見直す条件**: (1) 実際の埋込先prompt-engine側がPostgreSQL/Redis以外へ移行した場合、
  (2) apap-runtimeを別ホストへ埋め込む計画が具体化し、そのホストが異なるRDBMS/KVSを要求する場合。
  Port経由の抽象化を維持しているため、見直しは実装追加で対応可能（既存実装の破棄は不要）。
- **未決定**: Plugin署名の鍵配布・ローテーション方式（運用手順）は本ADRの範囲外。
- **関連**: ADR-0001（データストア選定、製品非依存の方針）、ADR-0002（Secret Store責務分界）、
  ADR-0017（同じ「実ホスト確認」方式の先例）、CLAUDE.md不変条件6。
