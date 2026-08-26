#!/usr/bin/env bash
# 一括検証スクリプト（コミット前に必ず実行）。
# build -> detekt -> kover(カバレッジ検証) -> アーキテクチャテスト -> Capability別E2Eスモークテスト
# を順に実行し、いずれかが失敗した時点で非0終了する。
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

GRADLEW="./gradlew"

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
