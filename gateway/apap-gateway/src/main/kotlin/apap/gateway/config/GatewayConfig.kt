package apap.gateway.config

import java.time.Duration

/**
 * ADR-0004: CIAP発行JWTのクレーム名をハードコードせず設定で宣言する。
 * ここに現れるのは「クレーム名」という設定値だけで、CIAP固有の型やクライアントは現れない
 * （CIAP実装詳細は`apap.gateway.auth.TokenVerifier`の実装クラス内に閉じる）。
 */
data class AuthClaimNames(
    val tenant: String = "tenant_id",
    val principal: String = "sub",
    val scopes: String = "scope",
    val capabilities: String? = "apap_capabilities",
)

/**
 * ADR-0004の`apap.auth`ブロック。
 *
 * [jwksUri]が未設定の場合、Gatewayは**認証を無効化せず起動を拒否する**
 * （認証の無効化を既定の縮退動作にすると、設定漏れがそのまま無認証公開になるため。
 * 開発時にトークン検証を迂回したい場合は`ApapGatewayBuilder.tokenVerifier`へ
 * テストダブルを明示的に注入すること——「明示的に選ぶ」形にしてある）。
 */
data class AuthConfig(
    val issuer: String,
    val audience: String,
    val jwksUri: String,
    val jwksCacheTtl: Duration = Duration.ofMinutes(DEFAULT_JWKS_CACHE_TTL_MINUTES),
    val clockSkew: Duration = Duration.ofSeconds(DEFAULT_CLOCK_SKEW_SECONDS),
    val claims: AuthClaimNames = AuthClaimNames(),
    /** Admin系API（`/admin/v1` 配下）へのアクセスに必要なスコープ。 */
    val adminScope: String = DEFAULT_ADMIN_SCOPE,
) {
    companion object {
        const val DEFAULT_JWKS_CACHE_TTL_MINUTES = 10L
        const val DEFAULT_CLOCK_SKEW_SECONDS = 60L
        const val DEFAULT_ADMIN_SCOPE = "apap.admin"
    }
}

/**
 * 11_デプロイメント図.md / 本タスク指示9: グレースフルシャットダウン。
 * PreStopで新規受付を止めてin-flightを完遂させ、Streamingには最大[streamingGraceSeconds]の猶予を与える。
 */
data class ShutdownConfig(
    val gracePeriodSeconds: Long = DEFAULT_GRACE_SECONDS,
    val streamingGraceSeconds: Long = DEFAULT_STREAMING_GRACE_SECONDS,
) {
    companion object {
        const val DEFAULT_GRACE_SECONDS = 30L

        /** 02_システム仕様.md 2.10「全体既定300s」に合わせる。 */
        const val DEFAULT_STREAMING_GRACE_SECONDS = 300L
    }
}

data class GatewayConfig(
    val port: Int = DEFAULT_PORT,
    val auth: AuthConfig,
    val shutdown: ShutdownConfig = ShutdownConfig(),
    /** 02_システム仕様.md 2.10: heartbeatは15秒毎。CLAUDE.md不変条件7に従い設定可能・既定は設計書通り。 */
    val sseHeartbeatSeconds: Long = DEFAULT_SSE_HEARTBEAT_SECONDS,
) {
    init {
        require(port in 1..MAX_PORT) { "port must be within 1..$MAX_PORT: $port" }
        require(sseHeartbeatSeconds > 0) { "sseHeartbeatSeconds must be positive: $sseHeartbeatSeconds" }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_SSE_HEARTBEAT_SECONDS = 15L
        private const val MAX_PORT = 65535
    }
}
