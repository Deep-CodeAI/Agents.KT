package agents_engine.model

import agents_engine.core.agent
import agents_engine.core.skill
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val MODEL = "gpt-oss:20b-cloud"
private const val HOST  = "localhost"
private const val PORT  = 11434

class OllamaClientIntegrationTest {

    private val greetTool = ToolDef("greet", "Greet a person by name") { it }
    private val client = OllamaClient(host = HOST, port = PORT, model = MODEL, temperature = 0.0, tools = listOf(greetTool))

    @Test
    fun `malformed native tool arguments are surfaced as invalid args instead of empty map`() {
        val parsed = parseToolArguments("""["Alice"]""")

        assertEquals("""["Alice"]""", parsed.rawArguments)
        assertTrue(parsed.parseError != null, "Malformed arguments should report a parse error")
        assertEquals(emptyMap<String, Any?>(), parsed.arguments)
    }

    @Tag("live-llm")
    @Test
    fun `returns text response for simple prompt`() {
        val response = client.chat(listOf(
            LlmMessage("user", "Reply with exactly the word: pong"),
        ))
        val text = assertIs<LlmResponse.Text>(response)
        assertTrue(text.content.isNotBlank())
    }

    @Tag("live-llm")
    @Test
    fun `follows inline tool call format`() {
        val messages = listOf(
            LlmMessage("system", """
                You are a tool-calling assistant. When asked to use a tool, output ONLY a JSON object — no explanation, no extra text.

                Format:
                {"tool": "<name>", "arguments": {"<key>": "<value>"}}

                Example:
                User: Greet Bob
                Assistant: {"tool": "greet", "arguments": {"name": "Bob"}}

                Available tools:
                - greet: Greet a person by name. Arguments: {name: string}
            """.trimIndent()),
            LlmMessage("user", "Greet Alice using the greet tool."),
        )
        val response = client.chat(messages)
        val toolCalls = assertIs<LlmResponse.ToolCalls>(response)
        val call = toolCalls.calls.first()
        println(call)
        assertEquals("greet", call.name)
        assertNotNull(call.arguments["name"])
    }

    @Tag("live-llm")
    @Test
    fun `full agentic loop — model calls tool and returns final answer`() {
        var toolCalled = false
        val a = agent<String, String>("test") {
            prompt("You are a tool-calling agent. You MUST use the available tools to complete tasks. Never answer directly without calling a tool first.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            tools { tool("greet", "Greet a person by name. Arguments: {name: string}") { args ->
                toolCalled = true
                "Hello, ${args["name"]}!"
            }}
            skills { skill<String, String>("s", "Greet someone using the greet tool") { tools("greet") } }
        }

        val result = a("Greet Alice.")
        assertTrue(toolCalled, "Model should have called the greet tool")
        assertTrue(result.contains("Alice", ignoreCase = true), "Final answer should mention Alice, got: $result")
    }
}
