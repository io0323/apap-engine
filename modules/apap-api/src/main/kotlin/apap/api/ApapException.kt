package apap.api

import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.NormalizedError

/**
 * [apap.runtime.ApapEngine]の実行系メソッドが失敗を通知する**公開例外**。
 *
 * 存在理由: `apap-runtime`は`apap-execution`/`apap-routing`/`apap-context`を`implementation`
 * スコープで依存しており、埋込ホスト（prompt-engine、`gateway/apap-gateway`）からは
 * `apap.execution.ExecutionFailedException`等の内部例外型が**コンパイル時に見えない**。
 * 内部例外をそのまま投げると、ホストは`catch (e: Exception)`で握るか、
 * 内部モジュールへ直接依存する（レイヤ違反）しか手が無くなる。
 *
 * そこで実行系の失敗はすべてこの型へ正規化して投げる。ホストが必要とする情報
 * （13_API設計.md 13.4のコード・retryable・retry_after_ms）は[NormalizedError]が保持しており、
 * `NormalizedError`/[ErrorCode]は`apap-domain`（`apap-runtime`が`api`スコープで公開）にあるため
 * ホストから到達できる。
 *
 * エラー分類そのものはエンジン側（02_システム仕様.md 2.11の表）で既に確定しているため、
 * ホストは[error]を読むだけでよく、分類をやり直してはならない。
 */
class ApapException(
    val error: NormalizedError,
    cause: Throwable? = null,
) : RuntimeException(error.message, cause) {
    /** 13.4のエラーコード。HTTPステータス・retryable既定値は[ErrorCode]自身が持つ。 */
    val code: ErrorCode get() = error.code

    /** Provider由来またはローカルRateLimiter由来の再試行猶予（ミリ秒）。無ければnull。 */
    val retryAfterMs: Long? get() = error.retryAfterMs
}
