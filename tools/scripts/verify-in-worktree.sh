#!/usr/bin/env bash
# IDEで開いたままCLIビルドを確実に検証するためのラッパー（ADR-0024）。
#
# IDE（redhat.java拡張の内蔵Gradleインポート）はこのプロジェクトディレクトリを対象に
# バックグラウンドビルドを継続的に実行しており、CLIビルドと同じ build/ 出力ディレクトリへ
# 同時に書き込むため、生成物が消失・破損することがある（GRADLE_USER_HOMEの分離だけでは
# 防げない）。git worktreeでIDEが監視していない別ディレクトリへ現在の作業内容
# （未コミットの変更も含む）を複製し、そこで検証する。
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
REPO_ROOT="$(pwd)"

WORKTREE_DIR="$(mktemp -d -t apap-engine-verify)"
cleanup() {
    git worktree remove "$WORKTREE_DIR" --force >/dev/null 2>&1 || true
    rm -rf "$WORKTREE_DIR"
}
trap cleanup EXIT

echo "==> git worktreeを作成: $WORKTREE_DIR"
git worktree add --detach --quiet "$WORKTREE_DIR" HEAD

echo "==> 未コミットの変更（追跡ファイルの変更 + 未追跡ファイル）をworktreeへ複製"
rsync -a --delete \
    --exclude=.git \
    --exclude=.gradle \
    --exclude=build \
    --exclude=bin \
    --exclude=.kotlin \
    --exclude='build-logic/.gradle' \
    --exclude='build-logic/build' \
    "$REPO_ROOT/" "$WORKTREE_DIR/"

JAVA_HOME_FOR_VERIFY="${JAVA_HOME:-}"
if [ -z "$JAVA_HOME_FOR_VERIFY" ] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME_FOR_VERIFY="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
if [ -z "$JAVA_HOME_FOR_VERIFY" ]; then
    echo "エラー: JDK21が見つかりません。JAVA_HOMEを指定するか、/usr/libexec/java_home -v 21 で解決できる状態にしてください。" >&2
    exit 1
fi

GRADLE_USER_HOME_FOR_VERIFY="${GRADLE_USER_HOME:-$HOME/.gradle-apap}"

echo "==> worktree内でverify.shを実行（JAVA_HOME=${JAVA_HOME_FOR_VERIFY}, GRADLE_USER_HOME=${GRADLE_USER_HOME_FOR_VERIFY}）"
# verify.shの成否に関わらずテスト実行数を集計したいので、ここでは即時終了させない。
set +e
(
    cd "$WORKTREE_DIR"
    JAVA_HOME="$JAVA_HOME_FOR_VERIFY" GRADLE_USER_HOME="$GRADLE_USER_HOME_FOR_VERIFY" ./tools/scripts/verify.sh "$@"
)
VERIFY_STATUS=$?
set -e

# worktreeはcleanup（trap EXIT）で削除されるため、テスト結果はここで集計しておく。
# リポジトリ側の build/test-results はこのworktree実行では更新されず、古い結果が残り続ける
# ——それを実行数の根拠にすると誤った結論に至る（実際に一度これで誤判定した）。
"$REPO_ROOT/tools/scripts/summarize-test-results.sh" "$WORKTREE_DIR" || true

exit "$VERIFY_STATUS"
