#!/usr/bin/env bash
# JUnit XML（<module>/build/test-results/**/TEST-*.xml）を集計し、全体とモジュール別の
# テスト実行数を標準出力に出す。
#
# 存在理由: verify-in-worktree.sh は一時worktreeで検証し、終了時にそれを削除する。
# その間リポジトリ側の build/test-results は更新されないため、あとからローカルの
# test-results を見ても「前回いつ実行されたか分からない古い結果」しか得られない。
# 実行数の突き合わせ（宣言した@Test数と実際に走った数の比較）を根拠にする場合、
# この古い結果を信じると誤った結論になる（CLAUDE.md不変条件9の「シグナルの不在」の一種）。
# そのため worktree 削除前にこのスクリプトで集計値を取り出して表示する。
#
# 使い方: summarize-test-results.sh [検索起点ディレクトリ]（既定: リポジトリルート）
set -euo pipefail

ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

if [ ! -d "$ROOT" ]; then
    echo "テスト結果の集計対象ディレクトリが存在しません: $ROOT" >&2
    exit 1
fi

XML_LIST="$(find "$ROOT" -path '*/build/test-results/*' -name 'TEST-*.xml' 2>/dev/null | sort || true)"

if [ -z "$XML_LIST" ]; then
    echo "==> テスト結果XMLが1件も見つかりませんでした（対象: ${ROOT}）。"
    echo "    テストが実行されていない可能性があります（この出力自体を成功と読まないこと）。"
    exit 1
fi

CLASS_COUNT="$(printf '%s\n' "$XML_LIST" | wc -l | tr -d ' ')"
echo "==> テスト実行数の集計（${CLASS_COUNT} クラス分のJUnit XML、対象: ${ROOT}）"

# 1行 = "モジュール テスト数 失敗 エラー スキップ" に正規化してからawkで合算する。
# モジュール名は build/test-results より前の最後の2セグメント（例: modules/apap-runtime）。
# bash 3.2（macOS標準）は ${var#"$OTHER"/} のネストした引用符を正しく扱えないため、
# 取り除く接頭辞を先に変数へ入れてから使う。
ROOT_PREFIX="$ROOT/"

printf '%s\n' "$XML_LIST" | while IFS= read -r xml; do
    [ -n "$xml" ] || continue
    rel="${xml#$ROOT_PREFIX}"
    module_path="${rel%%/build/test-results/*}"
    mod="$(printf '%s' "$module_path" | awk -F/ '{ if (NF>=2) print $(NF-1) "/" $NF; else print $0 }')"
    line="$(grep -m1 '<testsuite ' "$xml" 2>/dev/null || true)"
    [ -n "$line" ] || continue
    t="$(printf '%s' "$line" | sed -n 's/.*[^a-z]tests="\([0-9]*\)".*/\1/p')"
    f="$(printf '%s' "$line" | sed -n 's/.*failures="\([0-9]*\)".*/\1/p')"
    e="$(printf '%s' "$line" | sed -n 's/.*errors="\([0-9]*\)".*/\1/p')"
    s="$(printf '%s' "$line" | sed -n 's/.*skipped="\([0-9]*\)".*/\1/p')"
    printf '%s %s %s %s %s\n' "$mod" "${t:-0}" "${f:-0}" "${e:-0}" "${s:-0}"
done | awk '
    {
        mod = $1
        tests[mod] += $2; fails[mod] += $3; errs[mod] += $4; skips[mod] += $5; classes[mod] += 1
        T += $2; F += $3; E += $4; S += $5; C += 1
        if (!(mod in seen)) { seen[mod] = 1; order[++n] = mod }
    }
    END {
        fmt = "    %-36s %7s %7s %7s %7s %7s\n"
        printf fmt, "モジュール", "クラス", "テスト", "失敗", "エラー", "スキップ"
        for (i = 1; i <= n; i++) {
            m = order[i]
            printf "    %-36s %7d %7d %7d %7d %7d\n", m, classes[m], tests[m], fails[m], errs[m], skips[m]
        }
        printf "    %-36s %7d %7d %7d %7d %7d\n", "合計", C, T, F, E, S
        if (T == 0) {
            print "    警告: 実行されたテストが0件です。検査が機能していない可能性があります。"
        }
    }
'
