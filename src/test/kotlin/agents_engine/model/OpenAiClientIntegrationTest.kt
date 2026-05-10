package agents_engine.model

import agents_engine.core.agent
import agents_engine.core.skill
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Live OpenAI Chat Completions tests for [OpenAiClient] (#1656). Tagged
 * `live-llm` so they're excluded from the default `test` task; run with
 * `./gradlew integrationTest`.
 *
 * Key loading mirrors [ClaudeClientIntegrationTest]:
 * - reads `<repo-root>/.secrets/openai-key` (gitignored)
 * - falls back to `OPENAI_API_KEY` env var
 * - skips via JUnit `Assumptions` if neither is present, so a fresh clone
 *   without a key is still green.
 *
 * Default model is small + cheap; override with `OPENAI_TEST_MODEL`.
 */
class OpenAiClientIntegrationTest {

    private val apiKey: String? = loadApiKey()
    private val model: String = System.getenv("OPENAI_TEST_MODEL") ?: "gpt-4o-mini"

    @Tag("live-llm")
    @Test
    fun `returns text response for simple prompt`() {
        assumeTrue(apiKey != null, "skipping: no OpenAI key at .secrets/openai-key or OPENAI_API_KEY")
        val client = OpenAiClient(apiKey = apiKey!!, model = model, temperature = 0.0, maxTokens = 64)

        val response = client.chat(listOf(
            LlmMessage("user", "Reply with exactly the word: pong"),
        ))

        val text = assertIs<LlmResponse.Text>(response)
        assertTrue(text.content.isNotBlank(), "expected non-blank text, got '${text.content}'")
        assertTrue(
            (text.tokenUsage?.total ?: 0) > 0,
            "expected positive token usage, got ${text.tokenUsage}",
        )
    }

    @Tag("live-llm")
    @Test
    fun `model invokes a tool when given one and asked to use it`() {
        assumeTrue(apiKey != null, "skipping: no OpenAI key at .secrets/openai-key or OPENAI_API_KEY")
        val greetTool = ToolDef("greet", "Greet a person by name. Argument: name (string).") { it }
        val client = OpenAiClient(
            apiKey = apiKey!!,
            model = model,
            temperature = 0.0,
            maxTokens = 256,
            tools = listOf(greetTool),
        )

        val response = client.chat(listOf(
            LlmMessage("system", "You are a tool-calling assistant. Always use the available tools when applicable."),
            LlmMessage("user", "Greet Alice using the greet tool."),
        ))

        // The adapter's job is to translate `tool_calls` faithfully; what
        // arguments the model picks given an under-specified schema is the
        // model's call. (Untyped ToolDef → schema is `properties: {}`, which
        // smaller models read as "no required fields.") End-to-end fidelity
        // with named args is exercised by the full-agentic-loop test below.
        val calls = assertIs<LlmResponse.ToolCalls>(response)
        val call = calls.calls.first()
        assertTrue(call.name == "greet", "expected greet, got ${call.name}")
    }

    @Tag("live-llm")
    @Test
    fun `full agentic loop on OpenAI — tool result flows back as final answer`() {
        assumeTrue(apiKey != null, "skipping: no OpenAI key at .secrets/openai-key or OPENAI_API_KEY")
        val key = apiKey!!
        var toolCalled = false

        val a = agent<String, String>("test-openai") {
            lateinit var greet: Tool<Map<String, Any?>, Any?>
            prompt(
                "You are a tool-calling agent. You MUST use the available tools to greet people. " +
                    "After the tool returns, repeat its result verbatim as your final answer.",
            )
            model {
                openai(model)
                apiKey = key
                temperature = 0.0
                maxTokens = 256
            }
            tools {
                greet = tool("greet", "Greet a person by name. Arguments: {name: string}") { args ->
                    toolCalled = true
                    "Hello, ${args["name"]}!"
                }
            }
            skills {
                skill<String, String>("s", "Greet someone using the greet tool") { tools(greet) }
            }
        }

        val out = runBlocking { a.invokeSuspend("Greet Alice") }
        assertTrue(toolCalled, "tool must have been invoked at least once")
        assertTrue(
            out.contains("Hello", ignoreCase = true),
            "final answer should echo the tool result; got: $out",
        )
    }

    private fun loadApiKey(): String? {
        val path: Path = Paths.get(".secrets", "openai-key")
        if (Files.isReadable(path)) {
            val raw = Files.readString(path).trim()
            if (raw.isNotEmpty()) return raw
        }
        return System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
    }
}
