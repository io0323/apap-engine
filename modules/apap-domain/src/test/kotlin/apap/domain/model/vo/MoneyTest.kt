package apap.domain.model.vo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyTest {
    @Test
    fun `rejects negative amount`() {
        assertThrows(IllegalArgumentException::class.java) { Money(BigDecimal("-1.00"), "USD") }
    }

    @Test
    fun `rejects non ISO4217 shaped currency`() {
        assertThrows(IllegalArgumentException::class.java) { Money(BigDecimal.ONE, "usd") }
        assertThrows(IllegalArgumentException::class.java) { Money(BigDecimal.ONE, "US") }
    }

    @Test
    fun `adds and subtracts same currency`() {
        val a = Money(BigDecimal("10.00"), "USD")
        val b = Money(BigDecimal("2.50"), "USD")
        assertEquals(Money(BigDecimal("12.50"), "USD"), a + b)
        assertEquals(Money(BigDecimal("7.50"), "USD"), a - b)
    }

    @Test
    fun `rejects mixed currency arithmetic`() {
        val usd = Money(BigDecimal.TEN, "USD")
        val jpy = Money(BigDecimal.TEN, "JPY")
        assertThrows(IllegalArgumentException::class.java) { usd + jpy }
        assertThrows(IllegalArgumentException::class.java) { usd - jpy }
        assertThrows(IllegalArgumentException::class.java) { usd.compareTo(jpy) }
    }

    @Test
    fun `equality ignores decimal scale differences`() {
        assertEquals(Money(BigDecimal("1.0"), "USD"), Money(BigDecimal("1.00"), "USD"))
        assertEquals(
            Money(BigDecimal("1.0"), "USD").hashCode(),
            Money(BigDecimal("1.00"), "USD").hashCode(),
        )
    }

    @Test
    fun `zero factory produces zero amount`() {
        assertEquals(BigDecimal.ZERO.compareTo(Money.zero("USD").amount), 0)
    }

    @Test
    fun `compareTo orders by amount within the same currency`() {
        val small = Money(BigDecimal("1.00"), "USD")
        val large = Money(BigDecimal("2.00"), "USD")
        assert(small < large)
        assert(large > small)
        assertEquals(0, small.compareTo(Money(BigDecimal("1.0"), "USD")))
    }

    @Test
    fun `equals is reflexive and rejects non-Money values`() {
        val money = Money(BigDecimal("1.00"), "USD")
        assertEquals(money, money)
        @Suppress("EqualsBetweenInconvertibleTypes")
        assertEquals(false, money.equals("1.00 USD"))
    }
}
