package apap.domain.model.vo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class IdsTest {
    @Test
    fun `accepts a valid ULID`() {
        val id = ProviderId(testUlid('A'))
        assertEquals(testUlid('A'), id.value)
    }

    @Test
    fun `rejects too short value`() {
        assertThrows(IllegalArgumentException::class.java) { ProviderId("TOOSHORT") }
    }

    @Test
    fun `rejects lowercase value`() {
        assertThrows(IllegalArgumentException::class.java) { ModelId(testUlid('a')) }
    }

    @Test
    fun `rejects excluded crockford characters`() {
        // 'I' はCrockford Base32で除外される文字
        assertThrows(IllegalArgumentException::class.java) { AliasId(testUlid('I')) }
    }

    @Test
    fun `equal values are equal ids`() {
        assertEquals(TenantId(testUlid('B')), TenantId(testUlid('B')))
    }

    @Test
    fun `all seven typed id classes validate ULID format`() {
        assertThrows(IllegalArgumentException::class.java) { RequestId("x") }
        assertThrows(IllegalArgumentException::class.java) { ConversationId("x") }
        assertThrows(IllegalArgumentException::class.java) { SessionId("x") }
        assertThrows(IllegalArgumentException::class.java) { TenantId("x") }
        RequestId(testUlid('C'))
        ConversationId(testUlid('D'))
        SessionId(testUlid('E'))
        TenantId(testUlid('F'))
    }
}
