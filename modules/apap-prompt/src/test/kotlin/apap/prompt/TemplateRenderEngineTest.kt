package apap.prompt

import apap.domain.model.prompt.PromptTemplate
import apap.domain.model.prompt.TemplateVariable
import apap.domain.model.vo.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TemplateRenderEngineTest {
    private val ctx = PromptStageContext(TenantId("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "trace-1")

    private fun template(
        body: String,
        variables: List<TemplateVariable> = emptyList(),
    ) = PromptTemplate(templateId = "01ARZ3NDEKTSV4RRFFQ69G5FAW", name = "t", body = body, variables = variables)

    @Test
    fun `substitutes a variable placeholder`() {
        val engine = TemplateRenderEngine()
        val rendered = engine.render(template("Hello {{name}}!"), mapOf("name" to "Alice"), ctx)
        assertEquals("Hello Alice!", rendered)
    }

    @Test
    fun `if block is kept when the variable is truthy`() {
        val engine = TemplateRenderEngine()
        val body = "Start{{#if flag}} shown{{/if}} End"
        assertEquals("Start shown End", engine.render(template(body), mapOf("flag" to "true"), ctx))
    }

    @Test
    fun `if block is dropped when the variable is falsy or absent`() {
        val engine = TemplateRenderEngine()
        val body = "Start{{#if flag}} shown{{/if}} End"
        assertEquals("Start End", engine.render(template(body), emptyMap(), ctx))
        assertEquals("Start End", engine.render(template(body), mapOf("flag" to "false"), ctx))
    }

    @Test
    fun `unless block is the inverse of if`() {
        val engine = TemplateRenderEngine()
        val body = "Start{{#unless flag}} shown{{/unless}} End"
        assertEquals("Start shown End", engine.render(template(body), emptyMap(), ctx))
        assertEquals("Start End", engine.render(template(body), mapOf("flag" to "true"), ctx))
    }

    @Test
    fun `falls back to TemplateVariable defaultValue when not supplied`() {
        val engine = TemplateRenderEngine()
        val spec = TemplateVariable(name = "greeting", required = true, defaultValue = "Hi")
        val rendered = engine.render(template("{{greeting}} there", listOf(spec)), emptyMap(), ctx)
        assertEquals("Hi there", rendered)
    }

    @Test
    fun `throws when a required variable has no value and no default`() {
        val engine = TemplateRenderEngine()
        val spec = TemplateVariable(name = "greeting", required = true, defaultValue = null)
        assertThrows(MissingTemplateVariableException::class.java) {
            engine.render(template("{{greeting}} there", listOf(spec)), emptyMap(), ctx)
        }
    }

    @Test
    fun `template functions supply dynamic values`() {
        val function =
            object : TemplateFunction {
                override val name = "tenant"

                override fun resolve(ctx: PromptStageContext): String = ctx.tenantId.value
            }
        val engine = TemplateRenderEngine(listOf(function))
        val rendered = engine.render(template("Tenant: {{tenant}}"), emptyMap(), ctx)
        assertEquals("Tenant: ${ctx.tenantId.value}", rendered)
    }

    @Test
    fun `explicit variables override function-supplied values`() {
        val function =
            object : TemplateFunction {
                override val name = "tenant"

                override fun resolve(ctx: PromptStageContext): String = "from-function"
            }
        val engine = TemplateRenderEngine(listOf(function))
        val rendered = engine.render(template("{{tenant}}"), mapOf("tenant" to "explicit"), ctx)
        assertEquals("explicit", rendered)
    }
}
