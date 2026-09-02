package apap.gateway.auth

import apap.domain.model.vo.TenantId
import apap.gateway.config.AuthConfig
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/**
 * ADR-0004の[TokenVerifier]実装。**CIAP固有の知識（JWT/JWKSライブラリ、クレーム名の解釈）は
 * このクラスの内側だけに存在する。**
 *
 * JWKSは[JwkProvider]でキャッシュする（TTLは`apap.auth.jwks_cache_ttl`）。鍵取得は
 * ブロッキングI/Oのため[Dispatchers.IO]へ逃がす。
 *
 * クレーム名は[AuthConfig.claims]で設定駆動（ADR-0004）。実値がCIAP側と合意前でも、
 * 設定を差し替えるだけで追従できるようにするのがこの設計の目的。
 */
class JwksTokenVerifier(
    private val config: AuthConfig,
    jwkProvider: JwkProvider = defaultJwkProvider(config),
) : TokenVerifier {
    private val jwkProvider: JwkProvider = jwkProvider

    override suspend fun verify(token: String): VerifiedCaller {
        val decoded = decodeAndVerify(token)
        return toVerifiedCaller(decoded)
    }

    private suspend fun decodeAndVerify(token: String): DecodedJWT =
        withContext(Dispatchers.IO) {
            try {
                val unverified = JWT.decode(token)
                val jwk = jwkProvider.get(unverified.keyId)
                val publicKey =
                    jwk.publicKey as? RSAPublicKey
                        ?: throw TokenVerificationException("Unsupported JWK key type for token verification")
                JWT
                    .require(Algorithm.RSA256(publicKey, null))
                    .withIssuer(config.issuer)
                    .withAudience(config.audience)
                    .acceptLeeway(config.clockSkew.seconds)
                    .build()
                    .verify(token)
            } catch (e: JWTVerificationException) {
                // 例外メッセージにトークン本体を載せない（ライブラリ側メッセージは載せてよいが、
                // 値そのものは含まれない）。
                throw TokenVerificationException("Token verification failed", e)
            } catch (e: com.auth0.jwk.JwkException) {
                throw TokenVerificationException("Signing key could not be resolved", e)
            }
        }

    // 必須クレームごとに「無ければ401」を個別のメッセージで返したいため、throwが3箇所になる。
    // まとめると「どのクレームが欠けているか」が分からなくなり、設定ミス（ADR-0004のクレーム名
    // 不一致）の切り分けができなくなる。
    @Suppress("ThrowsCount")
    private fun toVerifiedCaller(decoded: DecodedJWT): VerifiedCaller {
        val tenantRaw =
            decoded.getClaim(config.claims.tenant).asString()
                ?: throw TokenVerificationException("Token is missing the tenant claim")
        val principal =
            decoded.getClaim(config.claims.principal).asString()
                ?: decoded.subject
                ?: throw TokenVerificationException("Token is missing the principal claim")
        val tenantId =
            runCatching { TenantId(tenantRaw) }.getOrElse {
                // TenantIdはULID形式を要求する。値自体はエラーに含めない。
                throw TokenVerificationException("Tenant claim is not a valid tenant identifier")
            }
        return VerifiedCaller(tenantId = tenantId, principal = principal, scopes = extractScopes(decoded))
    }

    /**
     * `scope`クレームはRFC 8693/OAuth慣行で空白区切り文字列、実装によっては配列。両方を受ける。
     */
    private fun extractScopes(decoded: DecodedJWT): Set<String> {
        val claim = decoded.getClaim(config.claims.scopes)
        return when {
            claim.isNull || claim.isMissing -> emptySet()
            claim.asList(String::class.java) != null -> claim.asList(String::class.java).toSet()
            else ->
                claim
                    .asString()
                    ?.split(' ')
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    .orEmpty()
        }
    }

    private companion object {
        fun defaultJwkProvider(config: AuthConfig): JwkProvider =
            JwkProviderBuilder(URI(config.jwksUri).toURL())
                .cached(JWKS_CACHE_SIZE, config.jwksCacheTtl.seconds, TimeUnit.SECONDS)
                .rateLimited(JWKS_RATE_LIMIT_PER_MINUTE, 1, TimeUnit.MINUTES)
                .build()

        const val JWKS_CACHE_SIZE = 10L
        const val JWKS_RATE_LIMIT_PER_MINUTE = 10L
    }
}
