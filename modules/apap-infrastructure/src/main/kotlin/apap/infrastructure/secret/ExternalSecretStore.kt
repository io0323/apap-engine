package apap.infrastructure.secret

import apap.domain.model.vo.CredentialRef
import apap.domain.port.SecretStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class ExternalSecretStoreException(
    message: String,
) : RuntimeException(message)

/**
 * [SecretStore]の外部Store汎用実装（ADR-0002: HTTP経由で外部Storeを叩く。エンドポイント・認証方式は
 * 設定）。特定製品のAPI形状を前提としない、`GET {baseUrl}/{secretRef}` / `PUT {baseUrl}/{secretRef}`
 * （本文=秘密値そのもの、プレーンテキスト）という最小限の規約のみを課す。CLAUDE.md不変条件1と同種の
 * 精神（コード・ログ・例外メッセージに特定Secret Store製品名を書かない、ADR-0002制約）。
 *
 * 認証は[authHeader]（都度呼ばれる、値は毎回新たに取得——固定文字列をフィールド保持しない）で
 * リクエストヘッダへ注入する。認証方式自体（Bearer token、署名付きリクエスト等）は呼び出し側が
 * [authHeader]の実装で決める。
 */
class ExternalSecretStore(
    private val baseUrl: String,
    private val authHeader: () -> Pair<String, String>,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
) : SecretStore {
    override fun resolve(ref: CredentialRef): CharArray {
        val request = requestBuilder(ref.secretRef).GET().build()
        val response = send(request)
        if (response.statusCode() == HTTP_NOT_FOUND) {
            throw SecretNotFoundException(ref.secretRef)
        }
        requireSuccess(response, "resolve")
        return response.body().toCharArray()
    }

    override fun store(
        ref: CredentialRef,
        value: CharArray,
    ) {
        val request =
            requestBuilder(ref.secretRef)
                .PUT(HttpRequest.BodyPublishers.ofString(String(value)))
                .build()
        requireSuccess(send(request), "store")
    }

    private fun requestBuilder(secretRef: String): HttpRequest.Builder {
        val (headerName, headerValue) = authHeader()
        return HttpRequest
            .newBuilder(URI.create("$baseUrl/$secretRef"))
            .timeout(DEFAULT_TIMEOUT)
            .header(headerName, headerValue)
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        val handler = HttpResponse.BodyHandlers.ofString()
        return httpClient.send(request, handler)
    }

    private fun requireSuccess(
        response: HttpResponse<String>,
        operation: String,
    ) {
        if (response.statusCode() !in HTTP_OK_RANGE) {
            throw ExternalSecretStoreException(
                "Secret store $operation failed with HTTP status ${response.statusCode()}",
            )
        }
    }

    private companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val HTTP_NOT_FOUND = 404
        val HTTP_OK_RANGE = 200..299
    }
}
