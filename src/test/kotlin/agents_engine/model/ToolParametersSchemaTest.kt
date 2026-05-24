package agents_engine.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #2377 regression coverage for the tool `parameters` field across the
 * three first-party provider clients.
 *
 * Resolution order, applied identically by Ollama / OpenAI / Claude:
 *
 * 1. `argsType.jsonSchema()` if the typed-Args constructor was used.
 * 2. `parametersSchemaJson` if the caller (e.g., `McpClient`) carried a
 *    raw schema through.
 * 3. Permissive empty-object fallback (`additionalProperties:true`).
 *
 * The fallback stays permissive on purpose: untyped `ToolDef(name, desc)`
 * tools convey their args via description prose, and closing the schema
 * would tell the LLM "no args allowed" — breaking tool calling for every
 * legacy untyped tool (memory_*, forum_return, swarm). The proper fix is
 * to migrate the built-ins to typed `argsType`, which lands separately.
 */
class ToolParametersSchemaTest {

    private val emptyTool = ToolDef(
        name = "no_args",
        description = "Untyped tool with no schema",
        argsType = null,
        parametersSchemaJson = null,
        executor = { _ -> "ok" },
    )

    private val overrideTool = ToolDef(
        name = "with_override",
        description = "Untyped tool carrying an explicit schema",
        argsType = null,
        parametersSchemaJson = """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}""",
        executor = { _ -> "ok" },
    )

    // ── Ollama ────────────────────────────────────────────────────────────────

    @Test
    fun `Ollama fallback emits permissive additionalProperties`() {
        val body = stubbedOllama(listOf(emptyTool)).buildRequestJson(emptyList())
        assertHasFallback(body)
    }

    @Test
    fun `Ollama uses parametersSchemaJson override when present`() {
        val body = stubbedOllama(listOf(overrideTool)).buildRequestJson(emptyList())
        assertContainsOverride(body)
    }

    // ── OpenAI ────────────────────────────────────────────────────────────────

    @Test
    fun `OpenAI fallback emits permissive additionalProperties`() {
        val body = stubbedOpenAi(listOf(emptyTool)).buildRequestJson(emptyList())
        assertHasFallback(body)
    }

    @Test
    fun `OpenAI uses parametersSchemaJson override when present`() {
        val body = stubbedOpenAi(listOf(overrideTool)).buildRequestJson(emptyList())
        assertContainsOverride(body)
    }

    // ── Claude ────────────────────────────────────────────────────────────────

    @Test
    fun `Claude fallback emits permissive additionalProperties`() {
        val body = stubbedClaude(listOf(emptyTool)).buildRequestJson(listOf(LlmMessage("user", "x")))
        assertHasFallback(body)
    }

    @Test
    fun `Claude uses parametersSchemaJson override when present`() {
        val body = stubbedClaude(listOf(overrideTool)).buildRequestJson(listOf(LlmMessage("user", "x")))
        assertContainsOverride(body)
    }

    // ── Stub builders ─────────────────────────────────────────────────────────

    private fun stubbedOllama(tools: List<ToolDef>): OllamaClient =
        object : OllamaClient(model = "test", tools = tools) {}

    private fun stubbedOpenAi(tools: List<ToolDef>): OpenAiClient =
        object : OpenAiClient(apiKey = "k", model = "test", tools = tools) {}

    private fun stubbedClaude(tools: List<ToolDef>): ClaudeClient =
        object : ClaudeClient(apiKey = "k", model = "test", tools = tools) {}

    private fun assertHasFallback(body: String) {
        assertTrue(
            body.contains(""""additionalProperties":true"""),
            "Expected permissive fallback schema, got: $body",
        )
    }

    private fun assertContainsOverride(body: String) {
        assertTrue(
            body.contains(""""required":["q"]"""),
            "parametersSchemaJson override not forwarded: $body",
        )
        assertFalse(
            body.contains(""""additionalProperties":true"""),
            "Override path leaked the permissive fallback: $body",
        )
    }
}
