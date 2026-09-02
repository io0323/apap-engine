package apap.gateway.openapi

import apap.domain.model.vo.ErrorCode
import apap.gateway.catalog.EndpointCatalog
import apap.gateway.catalog.EndpointStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `docs/openapi/apap-v1.yaml` と実装の乖離検知（本タスク作業3-1）。
 *
 * OpenAPI定義は「外部に約束したAPI」であり、実装から独立して腐る。ここで固定するのは:
 * 1. **definitionのpathsと`EndpointCatalog`が過不足なく一致する**
 *    （実装したのに載せ忘れ／消したのに載ったまま、の両方を検出）。
 * 2. **未提供エンドポイントがdefinition上でも未提供と分かる**
 *    ——`x-apap-status: NOT_IMPLEMENTED`。消して隠さない（ADR-0027）。
 * 3. **エラーコードのenumが13.4＋ADRで認めた追加と一致する**。
 */
class OpenApiContractTest {
    private val spec: JsonNode by lazy {
        val file = File(repoRoot(), SPEC_PATH)
        check(file.exists()) { "OpenAPI definition not found: ${file.path}" }
        ObjectMapper(YAMLFactory()).readTree(file)
    }

    @Test
    fun `the definition declares exactly the endpoints the gateway implements`() {
        val documented = documentedOperations()
        assertTrue(
            documented.isNotEmpty(),
            "no operations were parsed from $SPEC_PATH; the parser is broken " +
                "(an empty set would let this test pass vacuously)",
        )

        val cataloged = EndpointCatalog.entries.map { it.method.uppercase() to it.path }.toSet()

        val missingFromSpec = cataloged - documented
        val extraInSpec = documented - cataloged
        if (missingFromSpec.isNotEmpty() || extraInSpec.isNotEmpty()) {
            fail<Unit>(
                buildString {
                    appendLine("$SPEC_PATH has drifted from EndpointCatalog.")
                    if (missingFromSpec.isNotEmpty()) {
                        appendLine("Implemented/known but absent from the definition (clients cannot discover them):")
                        missingFromSpec.sortedBy { it.second }.forEach { appendLine("  ${it.first} ${it.second}") }
                    }
                    if (extraInSpec.isNotEmpty()) {
                        appendLine("Present in the definition but not in the catalog (promises nothing serves):")
                        extraInSpec.sortedBy { it.second }.forEach { appendLine("  ${it.first} ${it.second}") }
                    }
                },
            )
        }
    }

    @Test
    fun `endpoints that are not provided are marked as such in the definition`() {
        val mismatches =
            EndpointCatalog.entries.mapNotNull { spec ->
                val declared = operationStatus(spec.method, spec.path)
                val expected = spec.status.name
                if (declared == expected) null else "${spec.method} ${spec.path}: spec=$declared catalog=$expected"
            }
        assertTrue(
            mismatches.isEmpty(),
            "x-apap-status disagrees with EndpointCatalog. An endpoint listed in 13.1 but not provided must say so " +
                "in the API definition rather than looking available (ADR-0027):\n${mismatches.joinToString("\n")}",
        )
    }

    @Test
    fun `not-implemented operations document the 501 response`() {
        val missing501 =
            EndpointCatalog.entries
                .filter { it.status == EndpointStatus.NOT_IMPLEMENTED }
                .filterNot { operationNode(it.method, it.path)?.path("responses")?.has("501") == true }
        assertTrue(
            missing501.isEmpty(),
            "these unavailable endpoints do not document their 501 response:\n" +
                missing501.joinToString("\n") { "  ${it.method} ${it.path}" },
        )
    }

    @Test
    fun `the error code enum matches 13-4 plus the ADR-backed additions`() {
        val documented =
            spec
                .path("components")
                .path("schemas")
                .path("ErrorCode")
                .path("enum")
                .map { it.asText() }
                .toSet()

        val expected = ErrorCode.entries.map { it.name }.toSet() + GATEWAY_ADDITIONS
        assertEquals(
            expected,
            documented,
            "the OpenAPI ErrorCode enum has drifted from the implementation's closed set. " +
                "Codes are a public contract (ADR-0027 / ADR-0030).",
        )
    }

    /** `paths` の各エントリを (METHOD, path) へ展開する。 */
    private fun documentedOperations(): Set<Pair<String, String>> {
        val paths = spec.path("paths")
        val result = mutableSetOf<Pair<String, String>>()
        paths.fieldNames().forEach { path ->
            paths.path(path).fieldNames().forEach { method ->
                if (method.uppercase() in HTTP_METHODS) result += method.uppercase() to path
            }
        }
        return result
    }

    private fun operationNode(
        method: String,
        path: String,
    ): JsonNode? =
        spec
            .path("paths")
            .path(path)
            .path(method.lowercase())
            .takeIf { !it.isMissingNode }

    private fun operationStatus(
        method: String,
        path: String,
    ): String? = operationNode(method, path)?.path("x-apap-status")?.asText()

    private fun repoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts not found; cannot locate the repository root")
    }

    private companion object {
        const val SPEC_PATH = "docs/openapi/apap-v1.yaml"
        val HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
        val GATEWAY_ADDITIONS = setOf("NOT_IMPLEMENTED", "RESOURCE_NOT_FOUND")
    }
}
