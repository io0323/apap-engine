package apap.gateway.error

import apap.api.ApapException
import apap.domain.model.vo.ErrorCode
import apap.gateway.auth.TokenVerificationException

/**
 * apap-runtimeから上がってきた例外を13.4のエラー応答へ変換する。
 *
 * **ここでビジネス判断（エラー分類）をしない。** 実行系の失敗は`apap-runtime`が
 * [ApapException]へ正規化済みで、13.4のコード・`retryable`・`retry_after_ms`は
 * エンジン側（02_システム仕様.md 2.11の表）で確定している。Gatewayはそれを写すだけ。
 * ここで再分類すると2.11の表が二重管理になる。
 *
 * したがってこの`when`に並ぶのは「HTTP層でしか起きない失敗」だけであり、
 * ドメイン例外の一覧にはならない（[ExceptionMappingTest]がこの性質を固定する）。
 */
fun Throwable.toProblemDetails(requestId: String): ProblemDetails =
    when (this) {
        // Gateway自身が確定させた失敗（未提供エンドポイント・リクエスト形式・権限）。
        is ApiException -> ProblemDetails.of(error, message, requestId, retryAfterMs)

        // 実行系: エンジンが正規化済みのNormalizedErrorをそのまま写す。
        is ApapException ->
            ProblemDetails.of(
                error = ApiError.of(error.code),
                detail = error.message,
                requestId = requestId,
                retryAfterMs = error.retryAfterMs,
            )

        // 認証（ADR-0004）。トークンの内容は載せない。
        is TokenVerificationException ->
            ProblemDetails.of(
                ApiError.of(ErrorCode.UNAUTHENTICATED),
                message ?: "Authentication failed",
                requestId,
            )

        // DTO→ドメインVO変換時のrequire違反等（ApapEngineへ到達する前に起きたもの）。
        is IllegalArgumentException ->
            ProblemDetails.of(
                ApiError.of(ErrorCode.INVALID_REQUEST),
                message ?: "Invalid request",
                requestId,
            )

        else ->
            ProblemDetails.of(
                ApiError.of(ErrorCode.INTERNAL_ERROR),
                "An unexpected internal error occurred",
                requestId,
            )
    }
