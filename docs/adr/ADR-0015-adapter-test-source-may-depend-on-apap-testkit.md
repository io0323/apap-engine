# ADR-0015: adapters/*のtestソースセットはapap-testkitへの依存を許可する

## ステータス

Accepted（2026-08-21）

## コンテキスト

P1で導入した`AdapterDependencyRuleTest`（`modules/apap-domain/src/test/kotlin/apap/domain/architecture/AdapterDependencyRuleTest.kt`）は、`Konsist.scopeFromDirectory("adapters")`で`adapters/`配下の**全ファイル（mainとtestの両方）**を対象に、`apap.adapter.spi`以外の`apap.*` importを一律禁止していた。これはCLAUDE.md不変条件2「`adapters/*`は`apap-adapter-spi`のみに依存する」をtestソースにも機械的に適用したものだった。

一方、本セッション（Adapter SPI / TestKit / Mock Adapter実装）の完了条件は次の2つを同時に要求する。

1. `adapter-mock`が`AdapterContractTest`（`apap.testkit.contract`パッケージ、`modules/apap-testkit`）を継承したテストで全項目パスすること。
2. `adapter-mock`が`apap-adapter-spi`以外のAPAPモジュールに依存していないこと（アーキテクチャテストで確認）。

`AdapterContractTest`を`adapter-mock`のtestソースからimportする以上、既存の`AdapterDependencyRuleTest`をそのまま適用すると（1）が（2）の機械検証に違反してしまい、両立できない。

`CLAUDE.md`の設計書用語対応表は既に「`apap-testkit`は全PortのIn-Memory実装と**Adapter Contract Testの置き場**」と明記しており、Adapter実装のtestソースが`apap-testkit`を利用することは設計意図として織り込み済みである（`docs/design/15_Provider追加手順.md` 15.1 Step3の「SPI準拠テストキット」を具体化したものが本モジュールにあたる）。したがって問題は要件同士の矛盾ではなく、P1時点のアーキテクチャテストの対象範囲（main/testを区別しない）が、この設計意図をまだ反映できていなかったことにある。

## 決定

`AdapterDependencyRuleTest`の禁止ルールを、**mainソースセットのみ**に適用するよう変更する（Konsistの`KoScope.slice { it.resideInSourceSet("main") }`でmainのみへ絞り込む）。

- `adapters/*/src/main`: 従来通り`apap.adapter.spi`以外の`apap.*` importを禁止（Provider Plugin本体の依存境界。この検証は変更しない）。
- `adapters/*/src/test`: `apap.adapter.spi`に加え`apap.testkit`（およびそれが`api`で公開する`apap.domain`）への依存を許可する。これはテスト専用の依存であり、配布されるPlugin本体（mainソースセットの成果物）には一切含まれないため、CLAUDE.md不変条件2「Provider固有の知識はadapters/配下にのみ存在してよい／コアへの依存禁止」が保護しようとしている「配布物の依存境界」を損なわない。

## 影響（Consequences）

- **制約**: 新たにAdapter Pluginモジュールを追加する場合も、本番コード（`src/main`）は`apap-adapter-spi`のみに依存し、テストコード（`src/test`）でのみ`apap-testkit`（Contract Test・In-Memory Port実装）を利用できる。
- **見直す条件**: なし。mainとtestの依存境界を分けることは一般的なGradleプロジェクトの慣行であり、将来の設計変更を要しない。
- **関連**: `docs/design/15_Provider追加手順.md` 15.1 Step3、`docs/design/16_拡張ポイント.md` 16.1、CLAUDE.md 設計書用語対応表（`apap-testkit`の行）。
