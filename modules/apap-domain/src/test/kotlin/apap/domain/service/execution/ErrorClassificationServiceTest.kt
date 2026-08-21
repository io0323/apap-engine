package apap.domain.service.execution

import apap.domain.model.vo.AdapterErrorCategory
import apap.domain.model.vo.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 02_システム仕様.md 2.11の表をそのまま検証する。 */
class ErrorClassificationServiceTest {
    @Test
    fun `TRANSIENT is retryable and fallbackable`() {
        val error = ErrorClassificationService.classify(AdapterErrorCategory.TRANSIENT, "network error")
        assertTrue(error.retryable)
        assertTrue(error.fallbackable)
    }

    @Test
    fun `RATE_LIMITED is retryable and fallbackable`() {
        val error = ErrorClassificationService.classify(AdapterErrorCategory.RATE_LIMITED, "429")
        assertTrue(error.retryable)
        assertTrue(error.fallbackable)
        assertEquals(ErrorCode.RATE_LIMIT_EXCEEDED, error.code)
    }

    @Test
    fun `INVALID_REQUEST is neither retryable nor fallbackable`() {
        val error = ErrorClassificationService.classify(AdapterErrorCategory.INVALID_REQUEST, "bad request")
        assertFalse(error.retryable)
        assertFalse(error.fallbackable)
    }

    @Test
    fun `AUTH_ERROR is not retryable but is fallbackable`() {
        val error = ErrorClassificationService.classify(AdapterErrorCategory.AUTH_ERROR, "401")
        assertFalse(error.retryable)
        assertTrue(error.fallbackable)
    }

    @Test
    fun `CONTENT_FILTERED defaults to neither retryable nor fallbackable but is policy configurable`() {
        val default = ErrorClassificationService.classify(AdapterErrorCategory.CONTENT_FILTERED, "filtered")
        assertFalse(default.retryable)
        assertFalse(default.fallbackable)

        val allowed =
            ErrorClassificationService.classify(
                AdapterErrorCategory.CONTENT_FILTERED,
                "filtered",
                contentFilteredFallbackAllowed = true,
            )
        assertTrue(allowed.fallbackable)
    }

    @Test
    fun `MODEL_ERROR is retryable and fallbackable`() {
        val error = ErrorClassificationService.classify(AdapterErrorCategory.MODEL_ERROR, "schema violation")
        assertTrue(error.retryable)
        assertTrue(error.fallbackable)
    }

    @Test
    fun `PROVIDER_UNAVAILABLE is not retryable but is fallbackable`() {
        val error = ErrorClassificationService.classify(AdapterErrorCategory.PROVIDER_UNAVAILABLE, "503")
        assertFalse(error.retryable)
        assertTrue(error.fallbackable)
    }

    @Test
    fun `UNSUPPORTED_CAPABILITY is neither retryable nor fallbackable`() {
        val error = ErrorClassificationService.classify(AdapterErrorCategory.UNSUPPORTED_CAPABILITY, "unsupported")
        assertFalse(error.retryable)
        assertFalse(error.fallbackable)
        assertEquals(ErrorCode.CAPABILITY_NOT_AVAILABLE, error.code)
    }

    @Test
    fun `category is preserved and providerDetail is carried through`() {
        val error =
            ErrorClassificationService.classify(AdapterErrorCategory.TRANSIENT, "boom", providerDetail = "raw-detail")
        assertEquals(AdapterErrorCategory.TRANSIENT, error.category)
        assertEquals("raw-detail", error.providerDetail)
    }
}
