# CLAUDE.md — APAP (AI Provider Abstraction Platform)

> このファイルはリポジトリルートに `CLAUDE.md` として配置する。Claude Codeが毎セッション自動で読み込む。

## プロジェクト概要

APAPは、AIを利用する全システム（AI Agent / Workflow Engine / Backend / Web / Mobile / IoT / CLI / Batch）に対し、**特定のAI Providerに依存しない共通抽象化基盤**を提供する。Provider / Model / Version / Capability の追加・変更を、利用側アプリケーションの変更ゼロで実現することが唯一最大の目的。

- リポジトリ / `rootProject.name`: **`apap-engine`**
- 言語: Kotlin (JVM 21) / ビルド: Gradle Kotlin DSL マルチプロジェクト
- 主成果物: `modules/apap-runtime`（埋込用ライブラリ。別プロジェクト prompt-engine が依存する）
- 副成果物: `gateway/apap-gateway`（任意起動のHTTP/SSEサーバ）

### 名前空間の対応（混同注意）

| 概念 | 名前 |
|---|---|
| リポジトリ / Gradleルートプロジェクト | `apap-engine` |
| 埋込用ファサード**モジュール** | `modules/apap-runtime`（成果物 `apap-runtime`） |
| そのモジュールの**パッケージ** | `apap.runtime` |
| 公開**インターフェース**（型名） | `ApapEngine` / `ApapEngineBuilder` |

モジュール名（`apap-runtime`）と型名（`ApapEngine`）が異なるのは意図的。リポジトリ全体が「APAPエンジン」であり、その中の配線・ファサード担当が `apap-runtime`。**`modules/apap-engine` というモジュールは作らないこと**（ルートプロジェクトと同名になり混乱するため）。

## 設計書（一次情報）

実装判断に迷ったら**必ず** `docs/design/` を参照すること。推測で仕様を決めてはならない。

| 参照したい内容 | ファイル |
|---|---|
| 要件ID（FR-xxx / NFR-xxx）、ユースケース | `docs/design/01_要件定義.md` |
| モジュール責務、Routing決定手順、Retry/Fallback/CB/Cacheの**具体的な閾値** | `docs/design/02_システム仕様.md` |
| パッケージ構成、主要Interface（疑似コード）、DI方針、Strategy一覧 | `docs/design/03_基本設計.md` |
| Aggregate・VO・不変条件・Domain Service・Bounded Context | `docs/design/04_ドメイン設計.md` |
| 処理の呼び出し順序（Chat/Stream/ToolCalling/Fallback/Retry等） | `docs/design/05_シーケンス設計.md` |
| クラス関係 | `docs/design/06_クラス図.md` |
| 状態遷移（Provider/Model/CB/Request/Batch/Stream/Credential） | `docs/design/09_状態遷移図.md` |
| 分岐条件の網羅 | `docs/design/10_アクティビティ図.md` |
| テーブル定義・型・制約 | `docs/design/12_ER図.md` |
| REST API・エラーコード・HTTPステータス | `docs/design/13_API設計.md` |
| イベント名・発火元・購読先 | `docs/design/14_イベント一覧.md` |
| Adapter/Model/Capability追加手順 | `docs/design/15_Provider追加手順.md` |
| 拡張SPI一覧 | `docs/design/16_拡張ポイント.md` |

## 設計書との用語対応表

`docs/design/` は一次情報だが、実際のリポジトリ構成・命名と一致しない箇所がある（`docs/design-review.md` C3〜C5で検出）。**実装は右列（実装/実リポジトリ）を正とする**。設計書を読んで旧名称・旧配置をコードへ再導入しないこと。

| 設計書上の記載 | 該当箇所 | 実装（正） | 補足 |
|---|---|---|---|
| `class ApapFacade` | 03_基本設計.md 3.10 | `ApapEngine` / `ApapEngineBuilder`（`apap.runtime`パッケージ、`modules/apap-runtime`） | 同一物（SDK単一入口）を指す。設計書とCLAUDE.mdで名称が異なることが明記されていなかった（design-review C5）ため、ここで正式に対応付ける |
| `class AdminFacade` | 03_基本設計.md 3.10 | `ApapAdmin`（`ApapEngine.admin`経由で取得する管理API入口） | `ApapFacade`→`ApapEngine`のリネームに合わせ、対になる管理系ファサードも`Admin`接尾辞ではなく`ApapAdmin`とし、`ApapEngine`の子オブジェクトとして公開する |
| パッケージ構成ツリー中の `apap-engine`（ルート直下の1モジュールであるかのような記載例） | 03_基本設計.md 3.1 | モジュールとしては作らない。埋込用ファサードは `modules/apap-runtime` | `apap-engine`はリポジトリ全体（Gradle `rootProject.name`）の名前であり、同名モジュールを作るとルートプロジェクトと衝突し混乱するため意図的に避けている |
| `apap-gateway` が `apap/` 配下の1モジュールとして記載 | 03_基本設計.md 3.1 | `gateway/apap-gateway`（`modules/`の外、トップレベル別ディレクトリ） | 実リポジトリ構成が設計書の記載と異なる（design-review C3）。Presentation層を埋込ライブラリ本体（`modules/`）から明確に切り離すための意図的な配置 |
| `modules/apap-runtime`, `modules/apap-testkit` の記載なし | 03_基本設計.md 3.1（パッケージ構成一覧） | 実装に存在する追加モジュール。`apap-runtime`はDIコンポジションルート兼`ApapEngine`実装（配線コードのみ）、`apap-testkit`は全PortのIn-Memory実装とAdapter Contract Testの置き場 | design-review C4。設計書のパッケージ一覧はDomain〜Infrastructureの責務分割を示すものであり、埋込方式のための配線層・テスト基盤層は対象外だったための欠落と解釈する |

## 絶対に守る不変条件（違反はレビュー差し戻し対象）

1. **Vendor Neutral**: コード・設定・テスト・コメントに**特定AI Provider名/製品名/モデル名を一切書かない**。実Provider固有の知識は `adapters/` 配下にのみ存在してよい。コアのテストは `adapter-mock` のみを使う。
2. **依存方向**: `apap-domain` は何にも依存しない。すべての依存は内向き。`adapters/*` は `apap-adapter-spi` のみに依存する。Konsistテストで機械検証しており、これを緩めてはならない。
3. **公開APIにProvider/Model物理名を露出しない**: 利用側が指定できるのは Capability と Model Alias のみ（13.2）。応答にも既定で含めない。
4. **Credentialを保持しない**: `SecretAccessor.resolve()` で都度取得。フィールド保持・ログ出力・例外メッセージへの混入を禁止。
5. **暗黙のコンテキスト禁止**: ThreadLocal / CoroutineContext要素で実行状態を運ばない。`ExecutionContext` を引数で明示的に伝播する（3.15）。
6. **DI/フレームワーク非依存**: `apap-runtime` とその依存モジュールにDIコンテナ・アプリフレームワーク・ロギング実装を持ち込まない（SLF4J API / OpenTelemetry API までは可）。埋込先と競合させないため。
7. **数値は設計書から取る**: バックオフ200ms、CB失敗率50%/30秒窓、Sticky補正+0.05、スコア差0.02、Fallback既定3段、heartbeat 15秒など、閾値はすべて `docs/design/02_システム仕様.md` に定義済み。ハードコードする際は必ず設定可能にし、既定値を設計書と一致させる。
8. **設計書を書き換えない**: 設計と実装が矛盾した場合、`docs/design/` を編集せず `docs/adr/ADR-XXXX-*.md` を新規作成して判断と理由を記録し、その旨を報告する。

## 実装規約

- **エラー**: Adapterは必ず `AdapterException`（分類: TRANSIENT / RATE_LIMITED / INVALID_REQUEST / AUTH_ERROR / CONTENT_FILTERED / MODEL_ERROR / PROVIDER_UNAVAILABLE / UNSUPPORTED_CAPABILITY）を投げる。分類がRetry/Fallback挙動を決める（2.11の表が仕様）。コア側は `NormalizedError` に正規化してから利用側へ返す（13.4のコード体系）。
- **Streaming**: SPIは pull型 `AdapterStream(next/cancel)` を維持しつつ、Kotlin側は `suspend fun next(): AdapterChunk?` とし `Flow` へ変換する（suspendによる自然なバックプレッシャ）。**初回チャンク送出後はFallback不可**（2.10）。クライアント切断時はProviderへ `cancel()` を伝播する。
- **不変性**: Aggregate / VO は `data class` + `val`。状態遷移は必ずAggregateのメソッド経由で、不正遷移は専用例外を投げる（9章の遷移表が仕様）。
- **イベント**: 14章の名前と完全一致させる（勝手なリネーム禁止）。購読側は `eventId` で冪等に実装。
- **時刻・ID**: `Clock` と `IdGenerator` はPort化して注入（テストの決定性のため `System.currentTimeMillis()` / `UUID.randomUUID()` の直接呼び出し禁止）。
- **テスト**: 新規publicクラスにはテストを付ける。Port群はIn-Memory実装（`apap-testkit`）でUseCase単体テストをインフラなしで回す。Adapterは `apap-testkit` のContract Testに合格させる。
- **トレーサビリティ**: 機能実装時は `docs/traceability/requirements-matrix.md` に「FR/NFR-ID ↔ 実装クラス ↔ テスト」の行を追記する。

## コマンド

```bash
./gradlew build                  # フルビルド（コンパイル+テスト+detekt+arch test）
./gradlew test                   # テストのみ
./gradlew detekt ktlintFormat    # 静的解析 / 整形
./gradlew koverHtmlReport        # カバレッジ
./gradlew :gateway:apap-gateway:run   # Gateway起動（任意）
./tools/scripts/verify.sh        # 一括検証（コミット前に必ず実行）
docker compose -f tools/docker-compose.yaml up -d   # ローカル依存
```

## トラブルシューティング（Gradleビルドキャッシュ）

`build-logic`（Convention Plugin群のincludeBuild）を編集した直後や、依存関係のバージョンを変更した直後に、以下のようなエラーで `./gradlew build` が失敗することがある。

- `:build-logic:compilePluginsBlocks` が `Unable to parse script-resolver-environment argument implicit-imports=...` で失敗する
- `:build-logic:compilePluginsBlocks` が `Source file or directory not found: .../kotlin-dsl-external-plugin-spec-builders/.../PluginSpecBuilders.kt` で失敗する
- `:build-logic:compileKotlin` が、存在しないはずの `Unresolved reference` を大量に出す

これらは実装のバグではなく、Gradleのローカルビルドキャッシュ（`~/.gradle/caches/*/kotlin-dsl` 配下）が `build-logic` のprecompiled script plugin生成物について古い/矛盾した状態を返す既知の問題（Gradle 9.4.1で確認済み）。**同じ変更を2回試すより先に**、以下を試すこと。

```bash
./gradlew --stop
rm -rf ~/.gradle/caches/*/kotlin-dsl
./gradlew build --no-build-cache   # まずキャッシュ無効化で成功することを確認
./gradlew build                    # 通常設定（キャッシュ有効）で再度成功すればOK
```

`gradle.properties` の `org.gradle.caching=true` はこの問題への対処として無効化しない（通常時は問題なく動作するため）。上記の手順で解消しない場合のみ、原因調査を優先し、キャッシュ無効化を恒久対応にしない。

## 作業の進め方

- 変更は**モジュール単位で小さく**。1コミット1関心事。コミットメッセージは Conventional Commits（`feat(routing): ...`）。
- 実装前に該当設計書の節を読み、**満たす要件ID**をタスクに明記する。
- 未確定事項を勝手に決めない。判断が必要なら選択肢と推奨を提示して確認を求める。
- 完了報告には「実装した要件ID」「追加したテスト」「設計書との差分（あれば ADR番号）」を含める。
