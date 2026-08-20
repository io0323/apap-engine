# apap-engine

AI Provider Abstraction Platform（APAP）— AIを利用するすべてのシステムに対し、特定のAI Providerに依存しない共通抽象化基盤を提供するプラットフォームの実装リポジトリ。

設計の一次情報は [`docs/design/`](#設計書-docsdesign)、実装判断の記録は [`docs/adr/`](docs/adr/README.md)、実装規約は [`CLAUDE.md`](CLAUDE.md) を参照。詳しい用語・背景は `CLAUDE.md` の「プロジェクト概要」「設計書との用語対応表」を参照すること。

## リポジトリ名 `apap-engine` とモジュール `apap-runtime` の関係

- `apap-engine` は **リポジトリ名（Gradle `rootProject.name`）**。「APAPエンジン」全体を指す概念であり、単一のモジュールやクラスではない。
- `modules/apap-runtime` は、そのAPAPエンジンを**埋込ライブラリとして他プロジェクト（例: prompt-engine）から利用可能にするための配線・ファサードモジュール**。全コアモジュールに依存してよい唯一のモジュールであり、DIのコンポジションルートを持つ。公開する型は `ApapEngine` / `ApapEngineBuilder`（パッケージ `apap.runtime`）。
- そのため **`modules/apap-engine` というモジュールは意図的に作らない**（ルートプロジェクト名と同名になり混乱するため）。「リポジトリ全体 = apap-engine」「埋込用ファサード = apap-runtime」という対応を常に維持する。

## モジュール一覧

### modules/（コアライブラリ、Clean Architecture）

| モジュール | 責務 |
|---|---|
| `apap-api` | 公開契約（OpenAPI/Protobuf、共通DTO、エラーコード） |
| `apap-domain` | Domain層（Entity/VO/Aggregate/Domain Service/Event/Port）。依存ゼロの最内層 |
| `apap-adapter-spi` | Provider Adapter SPI（Adapter開発者向け公開契約） |
| `apap-application` | Application層（UseCase、Command/Query Handler） |
| `apap-execution` | Execution Engine / Retry / Fallback / Circuit Breaker / Streaming |
| `apap-routing` | Routing Engine / Policy評価 / Load Balancer |
| `apap-prompt` | Prompt Engine / Template / Validation / Optimization |
| `apap-context` | Context / Conversation / Session / Memory |
| `apap-provider` | Provider Manager / Model Manager / Capability Registry |
| `apap-plugin` | Plugin Manager / SPI定義の実行時契約 |
| `apap-cache` | Cache Engine（SPI + 既定実装） |
| `apap-cost` | Cost Engine / Quota |
| `apap-observability` | Monitoring / Audit / Tracing |
| `apap-infrastructure` | Repository実装 / Event Bus / Secret Store / KVS |
| `apap-runtime` | 埋込用ファサード・DIコンポジションルート（配線コードのみ。上記コアモジュール全部に依存してよい唯一のモジュール） |
| `apap-testkit` | 全PortのIn-Memory実装 + Adapter Contract Test基盤（テスト専用） |

### gateway/（Presentation層、任意起動）

| モジュール | 責務 |
|---|---|
| `apap-gateway` | REST/gRPC/SSE Controller、認証フィルタ。任意起動のHTTP/SSEサーバ |

### adapters/（Provider毎の独立Pluginモジュール）

| モジュール | 責務 |
|---|---|
| `adapter-mock` | コアのテストで使う、Provider非依存のモックAdapter実装 |
| `adapter-generic-http` | 汎用HTTP Provider向けAdapter実装の雛形 |

## 依存規則の要約

詳細は [`docs/design/08_パッケージ図.md`](docs/design/08_パッケージ図.md) と `build-logic` のConvention Plugin、および `modules/apap-domain/src/test` のKonsistアーキテクチャテストを参照。要点:

- `apap-domain` は他のどのモジュールにも依存しない（依存ゼロの最内層）。すべての依存は内向き。
- `apap-adapter-spi` は `apap-domain` の型のみ参照する。
- `adapters/*` は `apap-adapter-spi` のみに依存し、コアモジュール（domain/execution/routing/...）には依存しない。独立してビルド・配布・バージョニングされる。
- `apap-runtime` は全コアモジュールに依存してよいが、配線コード（DI構成・`ApapEngine`実装）のみを持つ。
- コード・設定・コメントに特定AI Provider名/製品名/モデル名を書かない（Vendor Neutral）。`modules/` と `gateway/` はKonsistテストで機械検証している。

## 標準コマンド

```bash
./gradlew build                       # フルビルド（コンパイル+テスト+detekt+ktlint+kover）
./gradlew test                        # テストのみ
./gradlew detekt ktlintFormat         # 静的解析 / 整形
./gradlew koverHtmlReport             # カバレッジレポート
./gradlew koverVerify                 # apap-domain/apap-application の行カバレッジ80%検証
./gradlew :gateway:apap-gateway:run   # Gateway起動（任意）
./tools/scripts/verify.sh             # 一括検証（コミット前に必ず実行）
docker compose -f tools/docker-compose.yaml up -d   # ローカル依存（RDBMS/分散KVS/メッセージング/観測基盤）
```

## 設計書（docs/design/）

一次情報。`docs/design/` 配下に `README.md` は存在しないため、章一覧はここに記載する（本セクションはナビゲーションのみで、設計内容そのものは編集しない）。

| 章 | ファイル | 内容 |
|---|---|---|
| 1 | [01_要件定義.md](docs/design/01_要件定義.md) | 要件定義（FR-xxx / NFR-xxx）、ユースケース |
| 2 | [02_システム仕様.md](docs/design/02_システム仕様.md) | モジュール責務、Routing決定手順、Retry/Fallback/CB/Cacheの具体的な閾値 |
| 3 | [03_基本設計.md](docs/design/03_基本設計.md) | パッケージ構成、主要Interface（疑似コード）、DI方針、Strategy一覧 |
| 4 | [04_ドメイン設計.md](docs/design/04_ドメイン設計.md) | Aggregate・VO・不変条件・Domain Service・Bounded Context |
| 5 | [05_シーケンス設計.md](docs/design/05_シーケンス設計.md) | 処理の呼び出し順序（Chat/Stream/ToolCalling/Fallback/Retry等） |
| 6 | [06_クラス図.md](docs/design/06_クラス図.md) | クラス関係 |
| 7 | [07_コンポーネント図.md](docs/design/07_コンポーネント図.md) | 全体コンポーネントと接続契約 |
| 8 | [08_パッケージ図.md](docs/design/08_パッケージ図.md) | モジュール依存と依存規則 |
| 9 | [09_状態遷移図.md](docs/design/09_状態遷移図.md) | 状態遷移（Provider/Model/CB/Request/Batch/Stream/Credential） |
| 10 | [10_アクティビティ図.md](docs/design/10_アクティビティ図.md) | 分岐条件の網羅 |
| 11 | [11_デプロイメント図.md](docs/design/11_デプロイメント図.md) | Kubernetes構成 / マルチリージョン / スケール方針 |
| 12 | [12_ER図.md](docs/design/12_ER図.md) | テーブル定義・型・制約 |
| 13 | [13_API設計.md](docs/design/13_API設計.md) | REST API・エラーコード・HTTPステータス |
| 14 | [14_イベント一覧.md](docs/design/14_イベント一覧.md) | イベント名・発火元・購読先 |
| 15 | [15_Provider追加手順.md](docs/design/15_Provider追加手順.md) | Adapter/Model/Capability追加手順 |
| 16 | [16_拡張ポイント.md](docs/design/16_拡張ポイント.md) | 拡張SPI一覧 |

## ドキュメント

- ADR（実装判断の記録）: [`docs/adr/`](docs/adr/README.md)
- 設計書レビュー・未決着事項: [`docs/design-review.md`](docs/design-review.md)
- トレーサビリティマトリクス（要件 ↔ 実装 ↔ テスト）: [`docs/traceability/requirements-matrix.md`](docs/traceability/requirements-matrix.md)
- 実装規約: [`CLAUDE.md`](CLAUDE.md)
