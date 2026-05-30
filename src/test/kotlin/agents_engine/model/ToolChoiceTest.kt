package agents_engine.model

import agents_engine.core.agent
import agents_engine.generation.LenientJsonParser
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2479 part 2 — vendor-neutral [ToolChoice] across all four built-in
 * providers. Pins:
 *
 * 1. Default is `Auto` (field omitted from the wire, pre-#2479-pt2 shape).
 * 2. Per-adapter wire mapping for each variant (OpenAI / DeepSeek / Claude
 *    / Ollama).
 * 3. `Specific(name)` fails fast at agent construction when the name isn't
 *    registered.
 * 4. Ollama emits a one-shot JUL warning for Required/Specific and treats
 *    them as no-ops; `None` is enforceable (tools array dropped).
 */
class ToolChoiceTest {

    private fun stubToolDef() = ToolDef(name = "write", description = "Write things") { _ -> "ok" }

    // -------- DSL + validation --------

    @Test
    fun `default toolChoice is Auto`() {
        val a = agent<String, String>("a") {
            lateinit var write: Tool<Map<String, Any?>, Any?>
            model { ollama("t") }
            tools { write = tool("write", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(write) } }
        }
        assertEquals(ToolChoice.Auto, a.toolChoice, "Auto is the default")
    }

    @Test
    fun `toolChoice DSL setter records the choice`() {
        val a = agent<String, String>("a") {
            lateinit var write: Tool<Map<String, Any?>, Any?>
            model { ollama("t") }
            tools { write = tool("write", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(write) } }
            toolChoice(ToolChoice.Required)
        }
        assertEquals(ToolChoice.Required, a.toolChoice)
    }

    @Test
    fun `Specific naming a registered tool succeeds`() {
        val a = agent<String, String>("a") {
            lateinit var write: Tool<Map<String, Any?>, Any?>
            model { ollama("t") }
            tools { write = tool("write", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(write) } }
            toolChoice(ToolChoice.Specific("write"))
        }
        assertEquals(ToolChoice.Specific("write"), a.toolChoice)
    }

    @Test
    fun `Specific naming an unregistered tool fails fast at agent construction`() {
        val ex = assertThrows<IllegalArgumentException> {
            agent<String, String>("a") {
                lateinit var write: Tool<Map<String, Any?>, Any?>
                model { ollama("t") }
                tools { write = tool("write", "") { _ -> "ok" } }
                skills { skill<String, String>("s", "") { tools(write) } }
                toolChoice(ToolChoice.Specific("doesNotExist"))
            }
        }
        assertTrue("doesNotExist" in ex.message!!, "error names the offending tool: ${ex.message}")
    }

    // -------- OpenAI wire mapping --------

    private fun openAiBody(choice: ToolChoice): Map<String, Any?> {
        val client = OpenAiClient(
            apiKey = "test",
            model = "gpt-4o",
            tools = listOf(stubToolDef()),
            toolChoice = choice,
        )
        val body = client.buildRequestJson(listOf(LlmMessage("user", "hi")))
        @Suppress("UNCHECKED_CAST")
        return LenientJsonParser.parse(body) as Map<String, Any?>
    }

    @Test
    fun `OpenAI - Auto omits tool_choice (pre-#2479-pt2 wire shape)`() {
        val body = openAiBody(ToolChoice.Auto)
        assertNull(body["tool_choice"], "Auto must not emit tool_choice field")
        assertNotNull(body["tools"], "tools array still present")
    }

    @Test
    fun `OpenAI - Required emits tool_choice required`() {
        val body = openAiBody(ToolChoice.Required)
        assertEquals("required", body["tool_choice"])
    }

    @Test
    fun `OpenAI - None emits tool_choice none AND drops tools array`() {
        val body = openAiBody(ToolChoice.None)
        assertEquals("none", body["tool_choice"])
        assertNull(body["tools"], "None must drop tools array — model cannot see them")
    }

    @Test
    fun `OpenAI - Specific emits typed function object`() {
        val body = openAiBody(ToolChoice.Specific("write"))
        @Suppress("UNCHECKED_CAST")
        val choice = body["tool_choice"] as Map<String, Any?>
        assertEquals("function", choice["type"])
        @Suppress("UNCHECKED_CAST")
        val function = choice["function"] as Map<String, Any?>
        assertEquals("write", function["name"])
    }

    // -------- Anthropic wire mapping --------

    private fun anthropicBody(choice: ToolChoice): Map<String, Any?> {
        val client = ClaudeClient(
            apiKey = "test",
            model = "claude-opus-4-7",
            tools = listOf(stubToolDef()),
            toolChoice = choice,
        )
        val body = client.buildRequestJson(listOf(LlmMessage("user", "hi")))
        @Suppress("UNCHECKED_CAST")
        return LenientJsonParser.parse(body) as Map<String, Any?>
    }

    @Test
    fun `Anthropic - Auto omits tool_choice`() {
        val body = anthropicBody(ToolChoice.Auto)
        assertNull(body["tool_choice"])
        assertNotNull(body["tools"])
    }

    @Test
    fun `Anthropic - Required emits type any (Anthropic vocabulary)`() {
        val body = anthropicBody(ToolChoice.Required)
        @Suppress("UNCHECKED_CAST")
        val choice = body["tool_choice"] as Map<String, Any?>
        assertEquals("any", choice["type"], "Anthropic spells 'required' as 'any'")
    }

    @Test
    fun `Anthropic - None drops tools array (no native none enum)`() {
        val body = anthropicBody(ToolChoice.None)
        assertNull(body["tool_choice"], "no tool_choice when None — model can't call what it can't see")
        assertNull(body["tools"], "tools field must be dropped for None")
    }

    @Test
    fun `Anthropic - Specific emits type tool plus name`() {
        val body = anthropicBody(ToolChoice.Specific("write"))
        @Suppress("UNCHECKED_CAST")
        val choice = body["tool_choice"] as Map<String, Any?>
        assertEquals("tool", choice["type"])
        assertEquals("write", choice["name"])
    }

    // -------- DeepSeek wire mapping (inherits OpenAI shape) --------

    @Test
    fun `DeepSeek - Required emits tool_choice required (OpenAI-compatible shape)`() {
        val client = DeepSeekClient(
            apiKey = "test",
            model = "deepseek-chat",
            tools = listOf(stubToolDef()),
            toolChoice = ToolChoice.Required,
        )
        val body = client.buildRequestJson(listOf(LlmMessage("user", "hi")))
        @Suppress("UNCHECKED_CAST")
        val parsed = LenientJsonParser.parse(body) as Map<String, Any?>
        assertEquals("required", parsed["tool_choice"])
    }

    // -------- Ollama best-effort + None enforcement --------

    @Test
    fun `Ollama - Auto includes tools array`() {
        val client = OllamaClient(model = "gpt-oss:120b-cloud", tools = listOf(stubToolDef()), toolChoice = ToolChoice.Auto)
        val body = client.buildRequestJson(listOf(LlmMessage("user", "hi")))
        @Suppress("UNCHECKED_CAST")
        val parsed = LenientJsonParser.parse(body) as Map<String, Any?>
        assertNotNull(parsed["tools"])
    }

    @Test
    fun `Ollama - None drops tools array (enforceable)`() {
        val client = OllamaClient(model = "gpt-oss:120b-cloud", tools = listOf(stubToolDef()), toolChoice = ToolChoice.None)
        val body = client.buildRequestJson(listOf(LlmMessage("user", "hi")))
        @Suppress("UNCHECKED_CAST")
        val parsed = LenientJsonParser.parse(body) as Map<String, Any?>
        assertNull(parsed["tools"], "Ollama None drops tools — that part is enforceable even without native tool_choice")
    }

    @Test
    fun `Ollama - Required is a best-effort no-op (tools still present, no tool_choice field)`() {
        val client = OllamaClient(model = "gpt-oss:120b-cloud", tools = listOf(stubToolDef()), toolChoice = ToolChoice.Required)
        val body = client.buildRequestJson(listOf(LlmMessage("user", "hi")))
        @Suppress("UNCHECKED_CAST")
        val parsed = LenientJsonParser.parse(body) as Map<String, Any?>
        // No native tool_choice — Ollama has no field for it
        assertNull(parsed["tool_choice"])
        // Tools array still present (Required can't force a tool call without the field)
        assertNotNull(parsed["tools"])
    }
}
