package apap.context

import apap.domain.model.vo.ErrorCode
import apap.domain.model.vo.TokenCount

/**
 * 圧縮後もcontext windowを超過する場合に送出する。13.4のCONTEXT_LENGTH_EXCEEDED（422）に対応する。
 * [DefaultContextManager.build]は同時に[apap.domain.event.TokenLimitExceeded]も発火する。
 */
class ContextLengthExceededException(
    val limit: TokenCount,
    val actual: TokenCount,
) : RuntimeException("Context length exceeded even after compaction: limit=${limit.value}, actual=${actual.value}") {
    val errorCode: ErrorCode = ErrorCode.CONTEXT_LENGTH_EXCEEDED
}
