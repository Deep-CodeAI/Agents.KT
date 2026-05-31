package agents_engine.model

import agents_engine.core.agent
import agents_engine.core.skill
import agents_engine.generation.Generable
import agents_engine.generation.Guide
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * #2701 — live OpenRouter Chat Completions smoke test.
 *
 * Uses `:free`-tier model variants so each run has zero cost — at the
 * maintainer's request. Free models are subject to upstream rate limits
 * and rotating availability; failures are usually transient (429 / 404
 * when a model is dropped from the free catalog), not code regressions.
 *
 * For that reason the suite is tagged `live-llm` (excluded from default
 * `:test`) rather than `live-cloud-api`. Run via `:integrationTest` or
 * with `-Dgroups=live-llm`. Flip to `live-cloud-api` once a stable,
 * tool-capable free model is reliably available — or switch to a paid
 * upstream model for default-suite coverage.
 *
 * Key loading mirrors the other hosted providers:
 * - reads `<repo-root>/.secrets/open-router-key` (gitignored)
 * - falls back to `OPENROUTER_API_KEY`
 * - skips via JUnit `Assumptions` if neither is present
 */
class OpenRouterClientIntegrationTest {

    private val apiKey: String? = loadApiKey()

    // Both verified to respond at branch-time. Override via env to swap if
    // a model rotates out of the free catalog.
    private val textModel: String =
        System.getenv("OPENROUTER_TEXT_MODEL") ?: "meta-llama/llama-3.2-3b-instruct:free"
    private val toolModel: String =
        System.getenv("OPENROUTER_TOOL_MODEL") ?: "openai/gpt-oss-20b:free"

    @Tag("live-llm")
    @Test
    fun `returns text response for simple prompt`() {
        assumeTrue(apiKey != null, "skipping: no OpenRouter key at .secrets/open-router-key or OPENROUTER_API_KEY")
        val client = OpenRouterClient(apiKey = apiKey!!, model = textModel, temperature = 0.0, maxTokens = 32)

        val response = client.chat(listOf(
            LlmMessage("user", "Reply with exactly the word: pong"),
        ))

        val text = assertIs<LlmResponse.Text>(response)
        assertTrue(text.content.isNotBlank(), "expected non-blank text, got '${text.content}'")
        assertTrue(
            (text.tokenUsage?.total ?: 0) > 0,
            "expected positive token usage, got ${text.tokenUsage}",
        )
        assertEquals("openrouter", text.tokenUsage?.provider)
    }

    @Tag("live-llm")
    @Test
    fun `streaming response emits chunks and OpenRouter usage at end`() = runBlocking {
        assumeTrue(apiKey != null, "skipping: no OpenRouter key")
        val client = OpenRouterClient(apiKey = apiKey!!, model = textModel, temperature = 0.0, maxTokens = 32)

        val chunks = client.chatStream(listOf(
            LlmMessage("user", "Say hello."),
        )).toList()

        // What we pin here: the streaming WIRE works end-to-end through
        // OpenRouter (chunks arrive, an End frame closes the stream, usage
        // is attached with our provider tag). We do NOT pin specific text
        // content — free-tier upstreams sometimes route their answer
        // through reasoning channels or truncate at zero on rate-pressure,
        // and chasing those flakes adds no signal about OpenRouter's
        // adapter correctness.
        assertTrue(chunks.isNotEmpty(), "expected streaming chunks")
        // End frame closes the stream cleanly. usage is opportunistic on the
        // free tier — some upstreams omit it from streamed responses, so we
        // assert presence-or-correctness rather than presence-and-correctness:
        // if usage IS reported, it MUST carry our provider tag.
        val end = assertIs<LlmChunk.End>(chunks.last())
        end.tokenUsage?.let {
            assertEquals("openrouter", it.provider, "if usage is reported, provider tag must be ours")
        }
    }

    @Tag("live-llm")
    @Test
    fun `model invokes typed tool through OpenRouter function calling`() {
        assumeTrue(apiKey != null, "skipping: no OpenRouter key")
        val tool = ToolDef(
            name = "report_number",
            description = "Report the exact integer requested by the user. Arguments: {value: integer}.",
            argsType = ReportNumberArgs::class,
        ) { it }
        val client = OpenRouterClient(
            apiKey = apiKey!!,
            model = toolModel,
            temperature = 0.0,
            maxTokens = 96,
            tools = listOf(tool),
        )

        val response = client.chat(listOf(
            LlmMessage(
                "system",
                "You are a tool-calling assistant. Always call the available tool; do not answer in text.",
            ),
            LlmMessage("user", """Call report_number with JSON arguments {"value":7}."""),
        ))

        val calls = assertIs<LlmResponse.ToolCalls>(response)
        val call = calls.calls.single()
        assertEquals("report_number", call.name)
        assertEquals(7, (call.arguments["value"] as Number).toInt())
        assertEquals("openrouter", calls.tokenUsage?.provider)
    }

    @Tag("live-llm")
    @Test
    fun `full agentic loop with OpenRouter free model + typed tool returns final answer`() {
        assumeTrue(apiKey != null, "skipping: no OpenRouter key")
        val key = apiKey!!
        val captured = mutableListOf<AddArgs>()

        val a = agent<String, String>("openrouter-add") {
            lateinit var add: Tool<AddArgs, AddResult>
            prompt(
                "You are a tool-calling agent. You MUST call add_numbers for arithmetic. " +
                    "After the tool returns, answer with the sum as plain text.",
            )
            model {
                openrouter(toolModel)
                apiKey = key
                temperature = 0.0
                maxTokens = 192
                openRouterHttpReferer = "https://agents-kt.dev"
                openRouterXTitle = "Agents.KT live integration"
            }
            tools {
                add = tool<AddArgs, AddResult>(
                    "add_numbers",
                    "Add two integers. Arguments: {a: integer, b: integer}.",
                ) { args ->
                    captured += args
                    AddResult(args.a + args.b)
                }
            }
            skills {
                skill<String, String>("add", "Add two numbers using add_numbers") { tools(add) }
            }
        }

        val out = runBlocking { a.invokeSuspend("Use add_numbers to add 17 and 25.") }

        assertTrue(captured.isNotEmpty(), "OpenRouter must invoke the typed tool; final answer was '$out'")
        assertEquals(17, captured.first().a)
        assertEquals(25, captured.first().b)
        assertTrue("42" in out, "expected final answer to include 42, got '$out'")
    }

    private fun loadApiKey(): String? {
        val path: Path = Paths.get(".secrets", "open-router-key")
        if (Files.isReadable(path)) {
            val raw = Files.readString(path).trim()
            if (raw.isNotEmpty()) return raw
        }
        return System.getenv("OPENROUTER_API_KEY")?.takeIf { it.isNotBlank() }
    }

    @Generable("Arguments for reporting one integer")
    data class ReportNumberArgs(
        @Guide("The exact integer to report") val value: Int,
    )

    @Generable("Arguments for adding two integers")
    data class AddArgs(
        @Guide("First addend") val a: Int,
        @Guide("Second addend") val b: Int,
    )

    @Generable("Sum result")
    data class AddResult(@Guide("a + b") val sum: Int)
}
