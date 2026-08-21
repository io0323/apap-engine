package apap.domain.model.vo

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NormalizedErrorTest {
    @Test
    fun `rejects blank message`() {
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedError(
                code = ErrorCode.PROVIDER_ERROR,
                category = AdapterErrorCategory.TRANSIENT,
                message = "  ",
                retryable = true,
                fallbackable = true,
            )
        }
    }

    @Test
    fun `accepts a fully populated error`() {
        NormalizedError(
            code = ErrorCode.RATE_LIMIT_EXCEEDED,
            category = AdapterErrorCategory.RATE_LIMITED,
            message = "rate limited",
            retryable = true,
            fallbackable = true,
            providerDetail = "raw provider error",
        )
    }
}
