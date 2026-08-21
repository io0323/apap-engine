package apap.domain.model.modelcatalog

import apap.domain.model.vo.AliasId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.testUlid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ModelAliasTest {
    private val modelA = ModelId(testUlid('A'))
    private val modelB = ModelId(testUlid('B'))

    @Test
    fun `requires target weights to sum to 100`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelAlias(AliasId(testUlid('C')), "chat-standard", listOf(AliasTarget(modelA, 50)))
        }
    }

    @Test
    fun `accepts targets summing to exactly 100`() {
        val alias =
            ModelAlias(
                AliasId(testUlid('C')),
                "chat-standard",
                listOf(AliasTarget(modelA, 90), AliasTarget(modelB, 10)),
            )
        assertEquals(100, alias.targets.sumOf { it.weight })
    }

    @Test
    fun `rejects duplicate modelId targets`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelAlias(
                AliasId(testUlid('C')),
                "chat-standard",
                listOf(AliasTarget(modelA, 50), AliasTarget(modelA, 50)),
            )
        }
    }

    @Test
    fun `rejects empty target list`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelAlias(AliasId(testUlid('C')), "chat-standard", emptyList())
        }
    }

    @Test
    fun `rejects a blank name`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelAlias(AliasId(testUlid('C')), " ", listOf(AliasTarget(modelA, 100)))
        }
    }

    @Test
    fun `AliasTarget rejects weight outside 0 to 100`() {
        assertThrows(IllegalArgumentException::class.java) { AliasTarget(modelA, -1) }
        assertThrows(IllegalArgumentException::class.java) { AliasTarget(modelA, 101) }
    }

    @Test
    fun `createWithModelStatusCheck rejects a target whose model is neither ACTIVE nor TESTING`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelAlias.createWithModelStatusCheck(
                AliasId(testUlid('C')),
                "chat-standard",
                listOf(AliasTarget(modelA, 100)),
            ) { ModelStatus.DEPRECATED }
        }
    }

    @Test
    fun `createWithModelStatusCheck accepts ACTIVE or TESTING targets`() {
        val alias =
            ModelAlias.createWithModelStatusCheck(
                AliasId(testUlid('C')),
                "chat-standard",
                listOf(AliasTarget(modelA, 90), AliasTarget(modelB, 10)),
            ) { modelId -> if (modelId == modelA) ModelStatus.ACTIVE else ModelStatus.TESTING }
        assertEquals(2, alias.targets.size)
    }
}
