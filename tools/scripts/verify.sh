#!/usr/bin/env bash
# 一括検証スクリプト（コミット前に必ず実行）。
# build -> detekt -> kover(カバレッジ検証) -> アーキテクチャテスト を順に実行し、
# いずれかが失敗した時点で非0終了する。
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

GRADLEW="./gradlew"

echo "==> [1/4] build (compile + test + detekt + ktlint)"
"$GRADLEW" build

echo "==> [2/4] detekt"
"$GRADLEW" detekt

echo "==> [3/4] kover（apap-domain / apap-application 行カバレッジ80%検証）"
"$GRADLEW" koverVerify

echo "==> [4/4] アーキテクチャテスト（Konsist, apap-domain）"
"$GRADLEW" :modules:apap-domain:test --tests "apap.domain.architecture.*"

echo "==> verify.sh: すべて成功しました"
