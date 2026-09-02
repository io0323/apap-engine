# ADR-0029: 埋込ホスト互換性を検証する専用モジュールを置き、統合ドキュメントのコード例をそこでコンパイルする

## ステータス

Accepted（2026-09-02）

## コンテキスト

P9で作成した `docs/integration/prompt-engine.md` に、**埋込ホスト（prompt-engine）では
コンパイルできないコード例**が載っていた。

```kotlin
import apap.execution.ExecutionFailedException as ApapExecutionFailedException
```

`apap-runtime` は `apap-execution` / `apap-routing` / `apap-context` を `implementation`
スコープで依存しているため、これらの型は埋込ホストのコンパイルクラスパスに現れない。
ガイドが指示する依存（`apap-runtime` と `apap-api` の2つのみ）だけを宣言したホストでは、
この `import` は解決できない。

**ドキュメントが検査対象外だったため、誰も気づけなかった。** ビルドもテストも緑のまま、
「ホストへ渡す準備ができた」と判断できてしまう状態だった。これはCLAUDE.md不変条件9が
言う「シグナルの不在を問題の不在と読む」形そのものである。

同種の問題は他にもあり得る: 公開APIのシグネチャが、ホストから見えない型を要求していても
apap-engine内部のテストでは決して露見しない（内部テストは全モジュールが見える環境で走るため）。

## 決定

### 1. `integration/host-compat` モジュールを新設する

依存は **`apap-runtime` と `apap-api` のみ**。他のAPAPモジュールを足さない
（足すとホストには見えない型がここでは見えてしまい、検証が無意味になる）。

このモジュールが**コンパイルできること自体が検証**である。

### 2. 統合ドキュメントのKotlinコード例は、このモジュール内の実コードとする

手作業の同期はしない。

- ソース側は `// docs:begin <id>` / `// docs:end <id>` で範囲を宣言する。
- ドキュメント側はコードフェンスの直前に `<!-- docs:<id> src=<path> -->` を置く。
- `DocumentedSnippetTest` が両者の一致を1文字単位で検証する。

さらに `DocumentedSnippetTest` は、**マーカーの無いKotlinコードフェンスを許さない**。
検証対象外にするなら `<!-- docs:illustrative reason=... -->` で理由付きの明示が要る。
これが無いと「マーカーを付け忘れた例だけが検査を素通りする」形になり、元の事故が再発する。

### 3. コンパイルクラスパスを機械検証する

`HostCompileClasspathTest` が、`apap-execution` / `apap-routing` / `apap-context` /
`apap-infrastructure-*` 等がmainのコンパイルクラスパスに**現れないこと**を確認する。
宣言（`build.gradle.kts`）ではなく**解決済みのクラスパス**を見る——宣言だけでは
推移的に入ってくるものを見落とす。

### 4. ホスト側の型は「写し」を置く

prompt-engineは別リポジトリであり、依存に加えると検証したい境界が壊れる。
そこで `HostPortMirror.kt` にPort/VOの**シグネチャだけ**を最小限で再現する。
振る舞いは持たない（足場であって再実装ではない）。

### 5. Springを使う例はコンパイル検証の対象外とし、その旨を明示する

Springはこのモジュールの依存に無い（1の制約）。`@Configuration`/`@Bean` で包む形は
`docs:illustrative` として理由付きで宣言し、**APAPをどう組み立てるかの部分**は
フレームワーク非依存の関数として必ずコンパイル検証する。

## 影響（Consequences）

### 本ADRの適用で即座に見つかった実際のバグ

`ApapEngine` の公開APIは `suspend fun execute(...)` と
`fun executeStream(...): Flow<ApapStreamChunk>` を持つが、`apap-runtime` は
`kotlinx-coroutines-core` を `api` スコープで公開していなかった。
ホストからは `Flow` も `suspend` 呼び出しも解決できない状態だった
（`apap-adapter-spi` は `AdapterStream.asFlow()` に対して同じ判断を正しく行っていた）。
`apap-runtime` に `api(kotlinx-coroutines-core)` を追加して解消した。

**このモジュールが無ければ、prompt-engine側で結線するまで発見されなかった。**

- **制約**: `integration/host-compat` の依存を増やすときは、それが「ホストが実際に宣言する
  依存」かどうかを必ず確認すること。テスト専用（adapter-mock等）は `testImplementation`
  に限定し、mainのコンパイルクラスパスへ入れない。
- **制約**: `ApapEngine`/`ApapAdmin`/`apap-api` の公開シグネチャに新しい型が現れたら、
  その型が `api` スコープで到達可能かを確認すること。到達できなければホストで壊れる。
- **見直す条件**: prompt-engine以外の埋込ホストが増えた場合、`HostPortMirror` を
  ホストごとに分けるか、Portの写し自体をやめて「APAP側APIの利用例」だけに絞るかを再検討する。
- **関連**: CLAUDE.md不変条件9、ADR-0016（SPI公開面）、ADR-0017（Jacksonバージョン整合）。
