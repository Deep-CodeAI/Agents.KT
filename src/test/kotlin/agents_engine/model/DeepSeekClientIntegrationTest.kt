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
 * Live DeepSeek Chat Completions smoke test. Tagged `live-llm` so it is
 * excluded from the default suite and only runs under `./gradlew integrationTest`.
 *
 * Key loading mirrors the other hosted providers:
 * - reads `<repo-root>/.secrets/deepseek-key` (gitignored)
 * - falls back to `DEEPSEEK_API_KEY`
 * - skips via JUnit `Assumptions` if neither is present
 */
class DeepSeekClientIntegrationTest {

    private val apiKey: String? = loadApiKey()
    private val model: String = System.getenv("DEEPSEEK_TEST_MODEL") ?: "deepseek-v4-flash"

    @Tag("live-llm")
    @Test
    fun `returns text response for simple prompt`() {
        assumeTrue(apiKey != null, "skipping: no DeepSeek key at .secrets/deepseek-key or DEEPSEEK_API_KEY")
        val client = DeepSeekClient(apiKey = apiKey!!, model = model, temperature = 0.0, maxTokens = 64)

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
    fun `streaming response emits text deltas and DeepSeek usage`() = runBlocking {
        assumeTrue(apiKey != null, "skipping: no DeepSeek key at .secrets/deepseek-key or DEEPSEEK_API_KEY")
        val client = DeepSeekClient(apiKey = apiKey!!, model = model, temperature = 0.0, maxTokens = 64)

        val chunks = client.chatStream(listOf(
            LlmMessage("user", "Count from 1 to 5 separated by spaces. Output only the numbers."),
        )).toList()

        assertTrue(chunks.isNotEmpty(), "expected streaming chunks")
        val end = assertIs<LlmChunk.End>(chunks.last())
        val text = chunks.dropLast(1)
            .filterIsInstance<LlmChunk.TextDelta>()
            .joinToString("") { it.text }
        assertTrue("1" in text && "5" in text, "expected count output, got '$text'")
        assertEquals("deepseek", end.tokenUsage?.provider)
        assertTrue((end.tokenUsage?.total ?: 0) > 0, "expected DeepSeek stream usage, got ${end.tokenUsage}")
    }

    @Tag("live-llm")
    @Test
    fun `model invokes typed tool through DeepSeek function calling`() {
        assumeTrue(apiKey != null, "skipping: no DeepSeek key at .secrets/deepseek-key or DEEPSEEK_API_KEY")
        val tool = ToolDef(
            name = "report_number",
            description = "Report the exact integer requested by the user. Arguments: {value: integer}.",
            argsType = ReportNumberArgs::class,
        ) { it }
        val client = DeepSeekClient(
            apiKey = apiKey!!,
            model = model,
            temperature = 0.0,
            maxTokens = 128,
            tools = listOf(tool),
        )

        val response = client.chat(listOf(
            LlmMessage("system", "You are a tool-calling assistant. Always call the available tool; do not answer in text."),
            LlmMessage("user", """Call report_number with JSON arguments {"value":7}."""),
        ))

        val calls = assertIs<LlmResponse.ToolCalls>(response)
        val call = calls.calls.single()
        assertEquals("report_number", call.name)
        assertEquals(7, (call.arguments["value"] as Number).toInt())
        assertEquals("deepseek", calls.tokenUsage?.provider)
    }

    @Tag("live-llm")
    @Test
    fun `full agentic loop with DeepSeek typed tool returns final answer`() {
        assumeTrue(apiKey != null, "skipping: no DeepSeek key at .secrets/deepseek-key or DEEPSEEK_API_KEY")
        val key = apiKey!!
        val captured = mutableListOf<AddArgs>()

        val a = agent<String, String>("deepseek-add") {
            lateinit var add: Tool<AddArgs, AddResult>
            prompt(
                "You are a tool-calling agent. You MUST call add_numbers for arithmetic. " +
                    "After the tool returns, answer with the sum as plain text.",
            )
            model {
                deepseek(model)
                apiKey = key
                temperature = 0.0
                maxTokens = 256
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

        assertTrue(captured.isNotEmpty(), "DeepSeek must invoke the typed tool; final answer was '$out'")
        assertEquals(17, captured.first().a)
        assertEquals(25, captured.first().b)
        assertTrue("42" in out, "expected final answer to include 42, got '$out'")
    }

    private fun loadApiKey(): String? {
        val path: Path = Paths.get(".secrets", "deepseek-key")
        if (Files.isReadable(path)) {
            val raw = Files.readString(path).trim()
            if (raw.isNotEmpty()) return raw
        }
        return System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }
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

    @Generable("Result of adding two integers")
    data class AddResult(
        @Guide("The sum of a and b") val sum: Int,
    )
}
