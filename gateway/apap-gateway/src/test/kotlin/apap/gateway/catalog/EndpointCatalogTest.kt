package apap.gateway.catalog

import apap.gateway.TestEngineFixture
import apap.gateway.VALID_ADMIN_TOKEN
import apap.gateway.apapGateway
import apap.gateway.json.GatewayJson
import apap.gateway.testGatewayConfig
import apap.gateway.testMetricsRenderer
import apap.gateway.testTokenVerifier
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * ADR-0027: [EndpointCatalog]を13.1の唯一の情報源として維持するための機械検証。
 *
 * 守りたい性質は2つ:
 * 1. **カタログに載っているパスは実際にルーティングされている**——載せたのに登録し忘れると
 *    404になり、「未提供（501）」ではなく「そんなAPIは無い」に化ける。
 * 2. **カタログは13_API設計.md 13.1の表を1行も落としていない**——表から消えると、
 *    そのエンドポイントは検討対象から静かに消える。
 *
 * 1はKtorの内部ルーティング構造を覗くのではなく、**実際にHTTPで叩いて404でないことを確かめる**。
 * 内部APIに依存しないぶん壊れにくく、「利用者から見て到達できるか」という本来の関心に一致する。
 */
class EndpointCatalogTest {
    @Test
    fun `every catalog entry is routable, so none of them silently 404`(): Unit =
        testApplication {
            val fixture = TestEngineFixture()
            val (renderer, _) = testMetricsRenderer()
            application { apapGateway(fixture.engine, testGatewayConfig(), testTokenVerifier(), renderer) }

            val unroutable = EndpointCatalog.entries.filter { spec -> client.probe(spec) }

            if (unroutable.isNotEmpty()) {
                fail<Unit>(
                    "These endpoints are listed in EndpointCatalog but are not routable (they answer 404, " +
                        "so clients cannot tell 'not provided' from 'does not exist'):\n" +
                        unroutable.joinToString("\n") { "  ${it.method} ${it.path}" },
                )
            }
        }

    @Test
    fun `the catalog covers every endpoint listed in 13-1 of the API design`() {
        val designPaths = pathsDeclaredInDesignDoc()
        assertTrue(
            designPaths.isNotEmpty(),
            "no endpoint paths were extracted from 13_API設計.md; the parser is broken " +
                "(an empty expectation would let this test pass vacuously)",
        )

        val cataloged = EndpointCatalog.entries.map { it.path.normalizePathParams() }.toSet()
        val missing = designPaths.filterNot { it in cataloged }
        if (missing.isNotEmpty()) {
            fail<Unit>(
                "These paths appear in 13.1 of the design document but are absent from EndpointCatalog. " +
                    "Do not drop rows — list them as NOT_IMPLEMENTED with a reason (ADR-0027):\n" +
                    missing.sorted().joinToString("\n") { "  $it" },
            )
        }
    }

    /**
     * 「ルートが存在しない」ことだけを判定する。
     *
     * 単純に404で判定してはいけない: `GET /admin/v1/aliases/{name}`のように、**ルートは在るが
     * 対象が無いので404**という正当な応答があるため（実際この素朴な判定は誤検出した）。
     * ハンドラへ到達していれば必ずProblem Details（`code`を持つJSON）が返るので、
     * 「404かつProblem Detailsでない」＝Ktorの既定404＝ルート未登録、と判定する。
     */
    private suspend fun HttpClient.probe(spec: EndpointSpec): Boolean {
        val path = spec.path.replace(Regex("""\{[^}]*}"""), SAMPLE_ULID)
        val response =
            request(path) {
                method = HttpMethod.parse(spec.method)
                header(HttpHeaders.Authorization, "Bearer $VALID_ADMIN_TOKEN")
                if (spec.method in METHODS_WITH_BODY) {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            }
        if (response.status.value != NOT_FOUND) return false
        val body = response.bodyAsText()
        val looksLikeProblemDetails =
            runCatching { GatewayJson.mapper.readTree(body).hasNonNull("code") }.getOrDefault(false)
        return !looksLikeProblemDetails
    }

    /**
     * 13.1の表からパスを抽出する。表のセルには
     * `/v1/memories / GET・DELETE /v1/memories/{id}, POST /v1/memories/search` のように
     * 複数パスが1セルへ詰め込まれた行があるため、`/v1/...`・`/admin/v1/...`らしき断片を
     * すべて拾う方式にする。
     */
    private fun pathsDeclaredInDesignDoc(): Set<String> {
        val doc = File(repoRoot(), "docs/design/13_API設計.md")
        check(doc.exists()) { "design document not found: ${doc.path}" }
        val section =
            doc
                .readText()
                .substringAfter("## 13.1 エンドポイント一覧")
                .substringBefore("## 13.2")
        return Regex("""/(?:admin/)?v1[A-Za-z0-9/{}_:.-]*""")
            .findAll(section)
            .map { it.value.trimEnd('.', ',', '|', ' ', '/') }
            .map { it.normalizePathParams() }
            // "/v1" 単体のような、エンドポイントではない断片を除く。
            .filter { it.count { ch -> ch == '/' } >= 2 }
            .toSet()
    }

    /** `{job_id}`/`{id}`/`{tenant_id}`等のパラメータ名の揺れを吸収する。 */
    private fun String.normalizePathParams(): String = Regex("""\{[^}]*}""").replace(this, "{}")

    private fun repoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts not found; cannot locate the repository root")
    }

    private companion object {
        const val NOT_FOUND = 404
        const val SAMPLE_ULID = "01ARZ3NDEKTSV4RRFFQ69G5FD0"
        val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH")
    }
}
