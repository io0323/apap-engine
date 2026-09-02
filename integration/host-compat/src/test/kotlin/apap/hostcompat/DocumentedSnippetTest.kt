package apap.hostcompat

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * ADR-0029: `docs/integration/prompt-engine.md` のKotlinコード例が、
 * このモジュール内の**実際にコンパイルされるコード**と一致していることを機械検証する。
 *
 * 守りたい性質は2つ:
 * 1. **ドキュメントのコードはコンパイル済みのコードと同一**（コピーがズレない）。
 * 2. **検証されていないKotlinコード例が黙って紛れ込まない**——検証対象外にするなら
 *    理由付きで明示的に宣言させる。理由の無い未検証ブロックはテストが落とす。
 *
 * 2がないと「マーカーを付け忘れた例だけが検査を素通りする」形になり、P9で起きた事故
 * （ホストから見えない型をimportする例がドキュメントに載っていた）が再発する。
 *
 * ドキュメント側の書式:
 * ```
 * <!-- docs:<id> src=<repo-relative path> -->
 * ```kotlin
 * ...コード...
 * ```
 * ```
 * あるいは検証対象外にする場合:
 * ```
 * <!-- docs:illustrative reason=<理由> -->
 * ```
 */
class DocumentedSnippetTest {
    @Test
    fun `every Kotlin code block in the integration guide is either verified or explicitly illustrative`() {
        val blocks = kotlinBlocks()
        assertTrue(
            blocks.isNotEmpty(),
            "no Kotlin code blocks were found in $DOC_PATH; the parser is broken " +
                "(zero blocks would make this test pass vacuously)",
        )

        val unmarked = blocks.filter { it.marker == null }
        if (unmarked.isNotEmpty()) {
            fail<Unit>(
                "These Kotlin code blocks carry no `<!-- docs:... -->` marker, so nothing verifies that they " +
                    "compile against the host's dependency set. Either back them with code in " +
                    "integration/host-compat, or mark them `<!-- docs:illustrative reason=... -->`:\n" +
                    unmarked.joinToString("\n") { "  $DOC_PATH:${it.startLine}" },
            )
        }
    }

    @Test
    fun `verified code blocks match their source region byte for byte`() {
        val verified = kotlinBlocks().filter { it.marker is Marker.Verified }
        assertTrue(
            verified.isNotEmpty(),
            "no verified code blocks were found; without any, this test would pass while the guide " +
                "could contain arbitrary uncompiled code",
        )

        val mismatches = mutableListOf<String>()
        verified.forEach { block ->
            val marker = block.marker as Marker.Verified
            val source = File(repoRoot(), marker.sourcePath)
            if (!source.exists()) {
                mismatches += "$DOC_PATH:${block.startLine}: source file not found: ${marker.sourcePath}"
                return@forEach
            }
            val region = extractRegion(source, marker.id)
            when {
                region == null ->
                    mismatches +=
                        "$DOC_PATH:${block.startLine}: region '${marker.id}' not found in ${marker.sourcePath} " +
                            "(expected `// docs:begin ${marker.id}` ... `// docs:end ${marker.id}`)"
                region != block.code ->
                    mismatches +=
                        "$DOC_PATH:${block.startLine}: the documented snippet has drifted from " +
                            "${marker.sourcePath} region '${marker.id}'.\n" +
                            "--- documentation ---\n${block.code}\n--- source ---\n$region"
            }
        }

        if (mismatches.isNotEmpty()) {
            fail<Unit>(
                "Documented code no longer matches the compiled source. Update the documentation from the " +
                    "source (the source is authoritative — it is what actually compiles):\n" +
                    mismatches.joinToString("\n\n"),
            )
        }
    }

    private sealed interface Marker {
        data class Verified(
            val id: String,
            val sourcePath: String,
        ) : Marker

        data class Illustrative(
            val reason: String,
        ) : Marker
    }

    private data class CodeBlock(
        val startLine: Int,
        val code: String,
        val marker: Marker?,
    )

    private fun kotlinBlocks(): List<CodeBlock> {
        val lines = File(repoRoot(), DOC_PATH).readLines()
        val blocks = mutableListOf<CodeBlock>()
        var index = 0
        while (index < lines.size) {
            if (lines[index].trim() == "```kotlin") {
                val end = (index + 1 until lines.size).firstOrNull { lines[it].trim() == "```" } ?: break
                val code = lines.subList(index + 1, end).joinToString("\n")
                blocks += CodeBlock(index + 1, code, markerAbove(lines, index))
                index = end + 1
            } else {
                index++
            }
        }
        return blocks
    }

    /** フェンスの直前（空行を挟んでもよい）にあるマーカーコメントを読む。 */
    private fun markerAbove(
        lines: List<String>,
        fenceIndex: Int,
    ): Marker? {
        for (i in (fenceIndex - 1) downTo maxOf(0, fenceIndex - MARKER_LOOKBACK)) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            VERIFIED_MARKER.find(line)?.let { return Marker.Verified(it.groupValues[1], it.groupValues[2]) }
            ILLUSTRATIVE_MARKER.find(line)?.let { return Marker.Illustrative(it.groupValues[1]) }
            // マーカー以外の内容に当たったら、そこで探索を打ち切る（別ブロックのマーカーを拾わない）。
            return null
        }
        return null
    }

    /**
     * `// docs:begin <id>` と `// docs:end <id>` に挟まれた範囲を、共通インデントを外して返す。
     */
    private fun extractRegion(
        source: File,
        id: String,
    ): String? {
        val lines = source.readLines()
        val begin = lines.indexOfFirst { it.trim() == "// docs:begin $id" }
        val end = lines.indexOfFirst { it.trim() == "// docs:end $id" }
        if (begin < 0 || end < 0 || end <= begin) return null
        val body = lines.subList(begin + 1, end)
        val indent =
            body
                .filter { it.isNotBlank() }
                .minOfOrNull { line -> line.takeWhile { it == ' ' }.length }
                ?: 0
        return body.joinToString("\n") { line -> line.drop(minOf(indent, line.takeWhile { it == ' ' }.length)) }
            .trim('\n')
    }

    private fun repoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts not found; cannot locate the repository root")
    }

    private companion object {
        const val DOC_PATH = "docs/integration/prompt-engine.md"
        const val MARKER_LOOKBACK = 4
        val VERIFIED_MARKER = Regex("""<!--\s*docs:([A-Za-z0-9-]+)\s+src=(\S+)\s*-->""")
        val ILLUSTRATIVE_MARKER = Regex("""<!--\s*docs:illustrative\s+reason=(.+?)\s*-->""")
    }
}
