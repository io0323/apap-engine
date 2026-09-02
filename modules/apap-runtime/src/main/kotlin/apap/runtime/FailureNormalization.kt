package apap.runtime

import apap.api.ApapException
import apap.context.ContextLengthExceededException
import apap.context.ConversationNotFoundException
import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.NormalizedError
import apap.execution.DuplicateRequestException
import apap.execution.ExecutionFailedException
import apap.execution.streaming.StreamAbortedBeforeFirstChunkException
import apap.routing.NoCandidateAvailableException
import kotlin.coroutines.cancellation.CancellationException

/**
 * `apap-runtime`の内側でしか見えない例外型を、公開例外[ApapException]へ正規化する。
 *
 * 13_API設計.md 13.4のコード付与は**ここで完結させる**。埋込ホスト（prompt-engine /
 * gateway/apap-gateway）が例外型を見て分類をやり直すと、02_システム仕様.md 2.11の表が
 * 二重管理になるため、ホストは`ApapException.error`を読むだけでよい状態にする。
 *
 * [CancellationException]はコルーチンのキャンセル制御そのものなので変換せず素通しする
 * （握り潰すと`executeStream`のキャンセル伝播——2.10「切断時はProviderへキャンセル伝播」——が壊れる）。
 *
 * この`when`は「例外型 → 13.4コード」の対応表であり、分岐数がそのまま「対応済みの型の数」を表す。
 * 分割すると対応表としての一覧性が失われるため、複雑度の閾値超過は許容する。
 */
@Suppress("CyclomaticComplexMethod")
internal fun Throwable.toApapException(): Throwable =
    when (this) {
        is CancellationException -> this
        is ApapException -> this

        // エンジンが既に正規化済みのもの。そのまま公開型へ移し替える。
        is ExecutionFailedException -> ApapException(error, this)
        is StreamAbortedBeforeFirstChunkException -> ApapException(normalizedError, this)

        is NoCandidateAvailableException ->
            ApapException(
                normalizedError(
                    ErrorCode.NO_CANDIDATE_AVAILABLE,
                    message ?: "No routing candidate available",
                    AdapterErrorCategory.PROVIDER_UNAVAILABLE,
                ),
                this,
            )

        is DuplicateRequestException ->
            ApapException(
                normalizedError(
                    ErrorCode.CONFLICT,
                    message ?: "Duplicate concurrent request",
                    AdapterErrorCategory.INVALID_REQUEST,
                ),
                this,
            )

        is ContextLengthExceededException ->
            ApapException(
                normalizedError(
                    errorCode,
                    message ?: "Context length exceeded",
                    AdapterErrorCategory.INVALID_REQUEST,
                ),
                this,
            )

        is ConversationNotFoundException ->
            ApapException(
                normalizedError(
                    ErrorCode.CONVERSATION_NOT_FOUND,
                    message ?: "Conversation not found",
                    AdapterErrorCategory.INVALID_REQUEST,
                ),
                this,
            )

        // 入力値がドメインVOの不変条件を満たさない（ULID形式違反など）。
        is IllegalArgumentException ->
            ApapException(
                normalizedError(
                    ErrorCode.INVALID_REQUEST,
                    message ?: "Invalid request",
                    AdapterErrorCategory.INVALID_REQUEST,
                ),
                this,
            )

        else -> this
    }

/**
 * 分類済みコードから[NormalizedError]を組み立てる。`retryable`は2.11の表に対応する
 * [ErrorCode.retryableByDefault]を用いる。
 *
 * ここを通る失敗はいずれもProvider呼出**そのもの**の失敗ではない（Provider由来の失敗は既に
 * `ExecutionFailedException`/`StreamAbortedBeforeFirstChunkException`として正規化済みで、
 * この関数を通らない）ため、`fallbackable`/`cbRecordable`は常にfalseとする——
 * 別Providerへ移しても結果が変わらず、CircuitBreakerに記録するとProviderの健全性評価を
 * 汚染するため。[category]は呼び出し側が最も近いものを明示する（既定値を置くと
 * 実態と合わない分類が黙って紛れ込むため必須引数にしている）。
 */
private fun normalizedError(
    code: ErrorCode,
    message: String,
    category: AdapterErrorCategory,
): NormalizedError =
    NormalizedError(
        code = code,
        category = category,
        message = message,
        retryable = code.retryableByDefault,
        fallbackable = false,
        cbRecordable = false,
    )
