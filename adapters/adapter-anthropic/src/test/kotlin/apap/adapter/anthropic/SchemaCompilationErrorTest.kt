package apap.adapter.anthropic

import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.service.execution.ErrorClassificationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * 構造化出力のスキーマが文法へコンパイルできないときの分類。
 *
 * 実APIには文法コンパイルの複雑さ上限があり、超えると400（`invalid_request_error`）で
 * "Schema is too complex for compilation" が返る。**再試行しても同じスキーマは同じ結果になる**ので、
 * これをTRANSIENTへ落とすと予算いっぱい同じ失敗を繰り返したうえでCircuit Breakerまで開く。
 * INVALID_REQUEST（2.11でRetry対象外・Fallback対象外・CB非計上）であることを固定する。
 */
class SchemaCompilationErrorTest {
    @Test
    fun `a schema that is too complex to compile is an invalid request, not a transient failure`() {
        val reply =
            HttpReply(
                status = 400,
                headers = emptyMap(),
                body =
                    """
                    {"type":"error","error":{"type":"invalid_request_error",
                     "message":"Schema is too complex for compilation"}}
                    """.trimIndent(),
            )

        val exception = ErrorMapper.toException(reply)
        assertEquals(AdapterErrorCategory.INVALID_REQUEST, exception.category)

        val normalized =
            ErrorClassificationService.classify(
                category = exception.category,
                message = exception.message.orEmpty(),
                providerDetail = exception.providerDetail,
            )
        assertFalse(normalized.retryable, "同じスキーマを送り直しても結果は変わりません")
        assertFalse(normalized.fallbackable, "スキーマ側の問題なので別Providerでも通りません")
    }
}
