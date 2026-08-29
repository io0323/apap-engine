#!/usr/bin/env bash
# 一括検証スクリプト（コミット前に必ず実行）。
# build -> detekt -> kover(カバレッジ検証) -> アーキテクチャテスト -> Capability別E2Eスモークテスト
# を順に実行し、いずれかが失敗した時点で非0終了する。
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

GRADLEW="./gradlew"

# build-logic/src/main/kotlin/apap.kotlin-common.gradle.kts の jvmToolchain(21) は
# 各モジュールのコンパイルには自動適用されるが、build-logic自身のスクリプトコンパイル
# （compilePluginsBlocks等）はGradleデーモンを起動したJVMでそのまま動く。JDK21が
# インストールされておらず17等にフォールバックしていると、Kotlinコンパイラデーモンの
# 引数解析エラーやkotlin-dslアクセサ生成の不整合として現れ、原因がわかりにくいため
# ここで早期に検出する（ADR-0024）。
JAVA_BIN="java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    JAVA_BIN="${JAVA_HOME}/bin/java"
fi
JAVA_VERSION_LINE="$("$JAVA_BIN" -version 2>&1 | head -1)"
if ! echo "$JAVA_VERSION_LINE" | grep -q '"21'; then
    echo "エラー: JDK 21が必要です（CLAUDE.md / jvmToolchain(21)）。検出されたバージョン: ${JAVA_VERSION_LINE}" >&2
    echo "JAVA_HOMEをJDK21に向けてから再実行してください（例: JAVA_HOME=\$(/usr/libexec/java_home -v 21)）。" >&2
    exit 1
fi

echo "==> [1/5] build (compile + test + detekt + ktlint)"
"$GRADLEW" build

echo "==> [2/5] detekt"
"$GRADLEW" detekt

echo "==> [3/5] kover（apap-domain / apap-application 行カバレッジ80%検証）"
"$GRADLEW" koverVerify

echo "==> [4/5] アーキテクチャテスト（Konsist, apap-domain）"
"$GRADLEW" :modules:apap-domain:test --tests "apap.domain.architecture.*"

# 着手前レビュー item2: 「実装済みだが実行経路から呼ばれていない」を機械的に検出するための
# Capability別E2Eスモークテスト（ExecutionEngineComposerで組み立てた実エンジン、adapter-mockのみ）。
# build（1/5）で既に実行済みだが、検知の要と位置づけて明示的な独立ステップとしても可視化する。
echo "==> [5/5] Capability別E2Eスモークテスト（apap-runtime）"
"$GRADLEW" :modules:apap-runtime:test --tests "apap.runtime.CapabilitySmokeTest"

echo "==> verify.sh: すべて成功しました"
