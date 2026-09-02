package apap.gateway.auth

import apap.domain.model.vo.TenantId

/**
 * ADR-0004: CIAP発行JWTの検証を行う腐敗防止層（ACL）の入口。
 *
 * このinterfaceの**シグネチャにCIAP固有の型・語彙を一切出さない**ことが目的である。
 * 実装（[JwksTokenVerifier]）だけがJWT/JWKSを知り、Gatewayの他の部分は
 * [VerifiedCaller]（テナント・プリンシパル・スコープ）しか見ない。
 * テストは自己署名JWTまたは単純なテストダブルで行い、CIAP実体に依存しない。
 */
fun interface TokenVerifier {
    /**
     * `Authorization: Bearer <token>`の`<token>`部分を検証する。
     *
     * @throws TokenVerificationException 署名不正・期限切れ・issuer/audience不一致・
     *   必須クレーム欠落など、**呼び出し側が401として扱うべきあらゆる失敗**。
     *   失敗理由の粒度は意図的に粗くし、トークンの内容を例外メッセージへ含めない
     *   （CLAUDE.md不変条件4と同じ理由: 資格情報をログ・例外へ混入させない）。
     */
    suspend fun verify(token: String): VerifiedCaller
}

/**
 * 検証済みトークンから取り出した、Gatewayが実際に必要とする情報のみ。
 * JWTのraw claimsをそのまま持ち回らない（CIAPのクレーム構造がGateway全体へ漏れるのを防ぐ）。
 */
data class VerifiedCaller(
    val tenantId: TenantId,
    val principal: String,
    val scopes: Set<String>,
) {
    init {
        require(principal.isNotBlank()) { "principal must not be blank" }
    }

    fun hasScope(scope: String): Boolean = scope in scopes
}

/**
 * 401として扱うべき検証失敗。[message]はクライアントへ返るため、トークン本体・鍵・
 * クレーム値を含めないこと（含めるとエラー応答経由で秘密が漏れる）。
 */
class TokenVerificationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
