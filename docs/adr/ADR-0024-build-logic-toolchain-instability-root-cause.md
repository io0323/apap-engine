# ADR-0024: `build-logic`のビルド不安定性（NoSuchFileException等）の根本原因とツールチェーン対処

## ステータス

Accepted（2026-08-29）

## コンテキスト

`./gradlew build`が`:build-logic:compilePluginsBlocks`ないし`:build-logic:compileKotlin`で、
以下のように再現性の乏しいエラーで断続的に失敗する事象が発生した。

- `Unable to parse script-resolver-environment argument implicit-imports=...`
- `Source file or directory not found: .../PluginSpecBuilders.kt`
- `Cannot access input property 'sources' ... Failed to normalize content of ...`
- `Unresolved reference 'jvmToolchain'` 等、本来存在するはずのAPIが解決できない大量のエラー

**着手時点の仮説（macOS FSEventsオーバーフロー）は誤りだった。** 以下を確認して否定した。

- クラウド同期（OneDrive/iCloud Drive/Dropbox/Google Drive）はいずれも本プロジェクトの
  ディレクトリを同期対象にしていない。
- Spotlight（`mdutil`/`mdfind`）は本ディレクトリを実質インデックス対象にしていない。
- 統合ログ（`log show`）に該当期間のFSEvents overflow記録は存在しない。
- `org.gradle.vfs.watch=false`を設定しても症状は変わらなかった。
- 完全に新規の`GRADLE_USER_HOME`（初回ダウンロードのGradle配布物）でも、既存のハッシュ値
  （例: `_9147c650f73598c23a071bd32573b572`）まで完全に一致する形で**決定論的に**再現した。
  タイミング依存の競合であれば同一ハッシュでの再現はしない。

実際に切り分けて判明した原因は、独立した3つの問題が重なったものだった。

### 原因1（主因）: IDE（Antigravity, `redhat.java`拡張の内蔵Gradleインポート）とのビルド出力競合

`redhat.java`拡張（VS Code系IDE用Java Language Server、Buildship相当のGradleインポート機能を
内蔵）が、**このプロジェクトディレクトリを対象にバックグラウンドでGradle 8.9のビルド
（デーモン）を継続的に実行し続けていた**（`cwd`が本プロジェクトルート、CPU使用率80%超・
稼働時間80分以上を観測）。

既存のCLAUDE.mdトラブルシューティング（「IDE（Java Language Server）との競合」）は
「ロック待ちで停止する」ことを前提にしており、`GRADLE_USER_HOME`をIDEと分離すること
（`GRADLE_USER_HOME=~/.gradle-apap`等）を対処としていた。しかし実際の障害モードはロック待ちの
停止ではなく、**IDE側のバックグラウンドビルドとCLIビルドが、`build-logic/build/`のような
プロジェクトディレクトリ内の生成物ディレクトリへ同時に書き込む**ことによる生成物の消失・破損
だった。`GRADLE_USER_HOME`はキャッシュ・デーモンのロック競合は分離するが、ビルド出力
ディレクトリ（`<module>/build/`）はプロジェクトツリー内で共有されるため分離できない。

`rm -rf build-logic/build`実行中に`Directory not empty`で失敗する事象、および
`stat`で「直前に確認できたファイルが次の瞬間には消えている」ことを実際に観測して確認した。

この競合は`.vscode/settings.json`の`gradle.autoDetect: off`・`java.import.gradle.enabled: false`
設定と、IDEウィンドウの2度のReload Windowでも解消しなかった（プロジェクトが既にIDEの
ワークスペース状態にインポート済みのため、設定変更が既存のインポート状態には遡って
効かないと考えられる）。最終的に**git worktreeでIDEが監視していない別ディレクトリに
チェックアウトし、そこでビルドする**ことで競合を確実に回避できることを確認した。

### 原因2: JDK 21が実機に一つもインストールされていなかった

CLAUDE.mdは「言語: Kotlin (JVM 21)」と明記し、`build-logic/src/main/kotlin/apap.kotlin-common.gradle.kts:8`
は`jvmToolchain(21)`を宣言している。しかし調査時点で実機にインストールされていたJDKは
17.0.18 / 11.0.26 / 8u322のみで、21系は皆無だった（`/usr/libexec/java_home -V`で確認）。

`jvmToolchain(21)`は各モジュールのコンパイルには`org.gradle.toolchains.foojay-resolver-convention`
経由で自動ダウンロード・適用されるが、**`build-logic`自身のスクリプトコンパイル
（`compilePluginsBlocks`・`compileKotlin`等）はGradleデーモンを起動したJVMでそのまま動く**ため、
この自動解決の対象外である。JDK21非搭載の状態でGradleデーモンがJDK17で起動すると、
Kotlinコンパイラデーモンの引数解析エラーとして症状が現れることを確認した
（`brew install openjdk@21`導入・`JAVA_HOME`をそちらに向けた状態で問題の一部が解消した）。

### 原因3: Gradle 9.4.1自身のkotlin-dslアクセサ生成の自己矛盾

原因1・2を取り除いた状態でも、`build-logic:compileKotlin`が下記で失敗するケースが残った。

```
Unresolved reference 'DefaultArtifactPublicationSet'.
Unresolved reference 'ApplicationPluginConvention'.
```

`build-logic/src/main/kotlin/apap.application.gradle.kts`は`application`コアプラグインを
適用している。Gradle 9.4.1のkotlin-dsl機能は、この`application`プラグインの型セーフ
アクセサ（Project拡張プロパティ）を自動生成する際、**Gradle自身が既に整理対象にしている
内部API**（`ApplicationPluginConvention`・`DefaultArtifactPublicationSet`）を参照する
コードを生成し、生成直後の同一Gradleバージョンでコンパイルできないという自己矛盾を起こす
（ビルドログ末尾に出る"Deprecated Gradle features were used in this build, making it
incompatible with Gradle 10"という警告と符合する）。`apap.application.gradle.kts`自体の
記述に問題はない。

`systemProp.org.gradle.kotlin.dsl.precompiled.accessors.strict=false`
（Gradle公式issue tracker上で同種の精密なアクセサ生成失敗に対する既知の回避策として
言及されている）を設定することで、strictなアクセサ検証を緩和し回避できることを確認した。

## 決定

1. **`org.gradle.vfs.watch=false`は設定しない。** FSEventsオーバーフローは根拠が反証されており、
   この設定は今回の問題を解決していない。無意味な設定を残さない。
2. **`build-logic/gradle.properties`（`kotlin.incremental=false`）は追加しない。** 同様に、
   これも今回の問題の解決には寄与していないことを、設定なしでのビルド成功で確認済み。
3. **`gradle.properties`に`systemProp.org.gradle.kotlin.dsl.precompiled.accessors.strict=false`
   を追加する。** 原因3（Gradle自身のアクセサ生成バグ）に対する、現時点で確認できている
   最小の回避策。
4. **`tools/scripts/verify.sh`の冒頭にJDK21チェックを追加する。** 原因2の再発を早期に
   検出し、エラーメッセージを分かりやすくするため。
5. **IDEのバックグラウンドGradleビルドとの競合（原因1）に対する恒久的なリポジトリ側の
   設定は行わない。** `.vscode/settings.json`での対処（`gradle.autoDetect`・
   `java.import.gradle.enabled`）は本調査で効果が確認できなかったため追加しない
   （設定自体は削除済み）。この競合はIDE拡張のワークスペース管理状態に起因し、
   リポジトリ設定だけでは解決しないため、運用上の回避策（後述）を代わりに文書化する。
6. **CLAUDE.mdの「IDE（Java Language Server）との競合」トラブルシューティングを、
   実際の障害モード（ロック待ちの停止ではなく、共有ビルド出力ディレクトリへの
   同時書き込みによる生成物の消失・破損）に基づいて訂正する。**

## 運用上の回避策（IDEを開いたままCLIビルドを確実に通したい場合）

`GRADLE_USER_HOME`の分離だけでは不十分。IDEでプロジェクトを開いたまま確実にビルドしたい場合は、
git worktreeでIDEが監視していない別ディレクトリにチェックアウトしてそこでビルドする。

```bash
git worktree add --detach /tmp/apap-engine-verify <branch-or-commit>
cd /tmp/apap-engine-verify
JAVA_HOME=$(/usr/libexec/java_home -v 21) GRADLE_USER_HOME=~/.gradle-apap ./tools/scripts/verify.sh
```

## 影響（Consequences）

- **制約**: 開発機にJDK21のインストールが必須（`brew install openjdk@21`等）。CI環境も同様。
- **見直す条件**: (1) Gradle側で原因3の自己矛盾が修正されたバージョンがリリースされた場合、
  `systemProp.org.gradle.kotlin.dsl.precompiled.accessors.strict=false`の要否を再検証する。
  (2) IDE拡張（`redhat.java`のGradleインポート機能）側で本セッション中に確認した競合が
  修正された場合、あるいはワークスペースの再インポートで`java.import.gradle.enabled=false`
  等の設定が有効に効くことが確認できた場合、運用上の回避策（worktree運用）の要否を見直す。
- **未決定のまま残る事項**: IDE側の競合を完全に無効化する設定（既存インポート済み状態への
  遡及適用）は本調査では見つけられなかった。JDTワークスペースメタデータのクリア等、
  より踏み込んだIDE側リセットで解決するかは未検証。
- **関連**: CLAUDE.md「トラブルシューティング（Gradleビルドキャッシュ）」
  「トラブルシューティング（IDE（Java Language Server）との競合）」（本ADRに基づき訂正）。
