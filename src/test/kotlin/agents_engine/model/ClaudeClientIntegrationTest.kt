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
 * Live Anthropic API tests for [ClaudeClient] (#1644). Tagged `live-llm` so
 * they're excluded from the default `test` task; run with
 * `./gradlew integrationTest`.
 *
 * Key loading:
 * - Reads from `<repo-root>/.secrets/anthropic-key` (gitignored).
 * - Falls back to the `ANTHROPIC_API_KEY` env var.
 * - If neither is present, every test in this class is **skipped** via
 *   JUnit `Assumptions` so a fresh clone without a key is still green.
 *
 * The model defaults to a small, fast variant; override with the
 * `CLAUDE_TEST_MODEL` env var when iterating against another model.
 */
class ClaudeClientIntegrationTest {

    private val apiKey: String? = loadApiKey()
    private val model: String = System.getenv("CLAUDE_TEST_MODEL") ?: "claude-haiku-4-5-20251001"

    @Tag("live-llm")
    @Test
    fun `returns text response for simple prompt`() {
        assumeTrue(apiKey != null, "skipping: no Anthropic key at .secrets/anthropic-key or ANTHROPIC_API_KEY")
        val client = ClaudeClient(apiKey = apiKey!!, model = model, temperature = 0.0, maxTokens = 64)

        val response = client.chat(listOf(
            LlmMessage("user", "Reply with exactly the word: pong"),
        ))

        val text = assertIs<LlmResponse.Text>(response)
        assertTrue(text.content.isNotBlank(), "expected non-blank text, got '${text.content}'")
        // Token usage should be reported by Anthropic on every successful call.
        assertTrue(
            (text.tokenUsage?.total ?: 0) > 0,
            "expected positive token usage, got ${text.tokenUsage}",
        )
    }

    @Tag("live-llm")
    @Test
    fun `model invokes a tool when given one and asked to use it`() {
        assumeTrue(apiKey != null, "skipping: no Anthropic key at .secrets/anthropic-key or ANTHROPIC_API_KEY")
        val greetTool = ToolDef("greet", "Greet a person by name. Argument: name (string).") { it }
        val client = ClaudeClient(
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

        val calls = assertIs<LlmResponse.ToolCalls>(response)
        val call = calls.calls.first()
        assertTrue(call.name == "greet", "expected greet, got ${call.name}")
        assertTrue(
            call.arguments["name"]?.toString()?.contains("Alice", ignoreCase = true) == true,
            "expected name=Alice, got ${call.arguments}",
        )
    }

    @Tag("live-llm")
    @Test
    fun `full agentic loop on Claude — tool result flows back as final answer`() {
        assumeTrue(apiKey != null, "skipping: no Anthropic key at .secrets/anthropic-key or ANTHROPIC_API_KEY")
        val key = apiKey!!
        var toolCalled = false

        val a = agent<String, String>("test-claude") {
            lateinit var greet: Tool<Map<String, Any?>, Any?>
            prompt(
                "You are a tool-calling agent. You MUST use the available tools to greet people. " +
                    "After the tool returns, repeat its result verbatim as your final answer.",
            )
            model {
                claude(model)
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
        // Prefer the file (allows committing other test infra without leaking
        // secrets through env-var snapshots). Fall back to env var for CI.
        val path: Path = Paths.get(".secrets", "anthropic-key")
        if (Files.isReadable(path)) {
            val raw = Files.readString(path).trim()
            if (raw.isNotEmpty()) return raw
        }
        return System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
    }
}
