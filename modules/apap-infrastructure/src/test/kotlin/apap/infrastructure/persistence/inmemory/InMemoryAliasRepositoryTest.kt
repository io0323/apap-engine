package apap.infrastructure.persistence.inmemory

import apap.domain.model.modelcatalog.AliasTarget
import apap.domain.model.modelcatalog.ModelAlias
import apap.domain.model.vo.AliasId
import apap.domain.model.vo.ModelId
import apap.domain.model.vo.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InMemoryAliasRepositoryTest {
    private val tenantId = TenantId("01ARZ3NDEKTSV4RRFFQ69G5FA0")
    private val modelId = ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA1")

    @Test
    fun `saves and finds an alias by name`() {
        val repo = InMemoryAliasRepository()
        val alias = ModelAlias(AliasId("01ARZ3NDEKTSV4RRFFQ69G5FA2"), "prod-chat", listOf(AliasTarget(modelId, 100)))
        repo.save(alias)

        assertEquals(alias, repo.findByName(tenantId, "prod-chat"))
        assertNull(repo.findByName(tenantId, "no-such-alias"))
    }

    @Test
    fun `listByModel returns aliases whose targets reference the model`() {
        val repo = InMemoryAliasRepository()
        val matching =
            ModelAlias(AliasId("01ARZ3NDEKTSV4RRFFQ69G5FA3"), "matches", listOf(AliasTarget(modelId, 100)))
        val other =
            ModelAlias(
                AliasId("01ARZ3NDEKTSV4RRFFQ69G5FA4"),
                "other",
                listOf(AliasTarget(ModelId("01ARZ3NDEKTSV4RRFFQ69G5FA5"), 100)),
            )
        repo.save(matching)
        repo.save(other)

        assertEquals(listOf(matching), repo.listByModel(modelId))
    }
}
