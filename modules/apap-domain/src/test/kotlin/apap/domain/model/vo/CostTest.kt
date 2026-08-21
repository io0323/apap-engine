package apap.domain.model.vo

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CostTest {
    @Test
    fun `accepts empty breakdown`() {
        Cost(Money(BigDecimal("1.00"), "USD"))
    }

    @Test
    fun `accepts breakdown summing to amount`() {
        Cost(
            amount = Money(BigDecimal("3.00"), "USD"),
            breakdown =
                mapOf(
                    "input" to Money(BigDecimal("1.00"), "USD"),
                    "output" to Money(BigDecimal("2.00"), "USD"),
                ),
        )
    }

    @Test
    fun `rejects breakdown not summing to amount`() {
        assertThrows(IllegalArgumentException::class.java) {
            Cost(
                amount = Money(BigDecimal("3.00"), "USD"),
                breakdown = mapOf("input" to Money(BigDecimal("1.00"), "USD")),
            )
        }
    }

    @Test
    fun `rejects breakdown entry with mismatched currency`() {
        assertThrows(IllegalArgumentException::class.java) {
            Cost(
                amount = Money(BigDecimal("1.00"), "USD"),
                breakdown = mapOf("input" to Money(BigDecimal("1.00"), "JPY")),
            )
        }
    }
}
