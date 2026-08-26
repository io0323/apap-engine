package apap.observability.audit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RegexMaskingStrategyTest {
    private val strategy = RegexMaskingStrategy()

    @Test
    fun `masks an email address`() {
        assertEquals("contact [MASKED] for help", strategy.mask("contact jane.doe@example.com for help"))
    }

    @Test
    fun `masks an ipv4 address`() {
        assertEquals("client at [MASKED] connected", strategy.mask("client at 192.168.1.10 connected"))
    }

    @Test
    fun `masks a long digit sequence resembling a card number`() {
        assertEquals("card [MASKED] charged", strategy.mask("card 4111 1111 1111 1111 charged"))
    }

    @Test
    fun `leaves text without any matching pattern unchanged`() {
        assertEquals("no sensitive content here", strategy.mask("no sensitive content here"))
    }
}
