package apap.gateway.error

import apap.domain.model.vo.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 13_API設計.md 13.4のエラーコード体系をクローズドセットとして固定する。
 *
 * エラーコードは**公開契約**であり、クライアントはこれで分岐する。勝手な追加・削除・
 * ステータス変更は破壊的変更なので、次の3点を機械検証する:
 *
 * 1. `apap.domain.model.vo.ErrorCode` が13.4の表と**過不足なく一致**する
 *    （設計書から直接読み取って突き合わせるので、表を書き写した定数と比較する形にしない）。
 * 2. HTTPステータスと`retryable`も13.4の表と一致する。
 * 3. Gateway側の追加コードは、ADRで根拠を示した既知のものだけに限られる。
 */
class ApiErrorClosedSetTest {
    @Test
    fun `the domain ErrorCode enum matches the 13-4 table exactly`() {
        val documented = documentedErrorCodes()
        assertTrue(
            documented.isNotEmpty(),
            "no error codes were parsed from 13_API設計.md; the parser is broken " +
                "(an empty expectation would let this test pass vacuously)",
        )

        val declared = ErrorCode.entries.map { it.name }.toSet()
        assertEquals(
            documented.keys,
            declared,
            "apap.domain.model.vo.ErrorCode has drifted from the 13.4 table. " +
                "Codes are a public contract: adding or removing one is a breaking change " +
                "(gateway-only additions belong in ApiError, not here — ADR-0027 / ADR-0030).",
        )
    }

    @Test
    fun `each documented code keeps its HTTP status and retryable flag`() {
        val documented = documentedErrorCodes()
        val mismatches =
            ErrorCode.entries.mapNotNull { code ->
                val expected = documented[code.name] ?: return@mapNotNull null
                val actual = DocumentedCode(code.httpStatus, code.retryableByDefault)
                if (expected == actual) null else "${code.name}: design=$expected implementation=$actual"
            }
        assertTrue(
            mismatches.isEmpty(),
            "These codes disagree with the 13.4 table on status/retryable:\n${mismatches.joinToString("\n")}",
        )
    }

    @Test
    fun `the gateway adds only the error codes justified by an ADR`() {
        val domainCodes = ErrorCode.entries.map { it.name }.toSet()
        val gatewayOnly = gatewayErrorCodes() - domainCodes

        assertEquals(
            EXPECTED_GATEWAY_ADDITIONS,
            gatewayOnly,
            "The gateway's error-code additions changed. Every addition to the 13.4 set needs an ADR " +
                "(NOT_IMPLEMENTED: ADR-0027, RESOURCE_NOT_FOUND: ADR-0030). " +
                "Adding one silently makes clients branch on an undocumented code.",
        )
    }

    private data class DocumentedCode(
        val status: Int,
        val retryable: Boolean,
    )

    /** 13.4「エラーコード体系」の表（`| code | HTTP | retryable | 説明 |`）を読む。 */
    private fun documentedErrorCodes(): Map<String, DocumentedCode> {
        val doc = File(repoRoot(), "docs/design/13_API設計.md")
        check(doc.exists()) { "design document not found: ${doc.path}" }
        val table =
            doc
                .readText()
                .substringAfter("### エラーコード体系")
                .substringBefore("## 13.5")
        return table
            .lineSequence()
            .mapNotNull { ROW.find(it) }
            .associate { match ->
                match.groupValues[1] to
                    DocumentedCode(
                        status = match.groupValues[2].toInt(),
                        retryable = match.groupValues[3].trim() == "true",
                    )
            }
    }

    /**
     * Gatewayが実際に返しうるコードの集合。`ApiError`のsealedな実装を列挙する
     * （`Domain`はドメイン側の全コードを覆う）。
     */
    private fun gatewayErrorCodes(): Set<String> =
        ErrorCode.entries.map { ApiError.of(it).code }.toSet() +
            setOf(ApiError.NotImplemented.code, ApiError.ResourceNotFound.code)

    private fun repoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts not found; cannot locate the repository root")
    }

    private companion object {
        /** `| INVALID_REQUEST | 400 | false | 入力スキーマ違反 |` */
        val ROW = Regex("""^\|\s*([A-Z_]+)\s*\|\s*(\d{3})\s*\|\s*(true|false)\s*\|""")

        val EXPECTED_GATEWAY_ADDITIONS = setOf("NOT_IMPLEMENTED", "RESOURCE_NOT_FOUND")
    }
}
