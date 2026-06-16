package agents_engine.model

import agents_engine.core.agent
import agents_engine.core.skill
import agents_engine.generation.Generable
import agents_engine.generation.Guide
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.abort
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
 * #1917 — live Google Gemini (Generative Language API) smoke test. Tagged `live-cloud-api` so it
 * runs in the default `:test` task when a key is present and skips cleanly otherwise (parity with
 * the DeepSeek / OpenAI / Anthropic live integration tests).
 *
 * Key loading:
 * - reads `<repo-root>/.secrets/gemini-key` (gitignored)
 * - falls back to `GEMINI_API_KEY`
 * - skips via JUnit `Assumptions` if neither is present
 */
class GeminiClientIntegrationTest {

    private val apiKey: String? = loadApiKey()
    private val model: String = System.getenv("GEMINI_TEST_MODEL") ?: "gemini-2.5-flash"

    @Tag("live-cloud-api")
    @Test
    fun `returns text response for simple prompt`() {
        assumeTrue(apiKey != null, "skipping: no Gemini key at .secrets/gemini-key or GEMINI_API_KEY")
        // 256 leaves headroom after gemini-2.5 default "thinking" (which spends maxOutputTokens).
        val client = GeminiClient(apiKey = apiKey!!, model = model, temperature = 0.0, maxTokens = 256)

        val response = skipIfEnvironmental {
            client.chat(listOf(LlmMessage("user", "Reply with exactly the word: pong")))
        }

        val text = assertIs<LlmResponse.Text>(response)
        assertTrue(text.content.isNotBlank(), "expected non-blank text, got '${text.content}'")
        assertTrue((text.tokenUsage?.total ?: 0) > 0, "expected positive token usage, got ${text.tokenUsage}")
        assertEquals("gemini", text.tokenUsage?.provider)
    }

    @Tag("live-cloud-api")
    @Test
    fun `streaming response emits text deltas and Gemini usage`() {
        assumeTrue(apiKey != null, "skipping: no Gemini key at .secrets/gemini-key or GEMINI_API_KEY")
        // gemini-2.5-* enables "thinking" by default and thinking tokens count against
        // maxOutputTokens — a tiny budget can be fully consumed by reasoning, leaving no visible
        // text. 512 gives the trivial prompt ample headroom after thinking.
        val client = GeminiClient(apiKey = apiKey!!, model = model, temperature = 0.0, maxTokens = 512)

        val chunks = skipIfEnvironmental {
            runBlocking {
                client.chatStream(listOf(
                    LlmMessage("user", "Count from 1 to 5 separated by spaces. Output only the numbers."),
                )).toList()
            }
        }

        assertTrue(chunks.isNotEmpty(), "expected streaming chunks")
        val end = assertIs<LlmChunk.End>(chunks.last())
        val text = chunks.dropLast(1)
            .filterIsInstance<LlmChunk.TextDelta>()
            .joinToString("") { it.text }
        assertTrue("1" in text && "5" in text, "expected count output, got '$text'")
        assertEquals("gemini", end.tokenUsage?.provider)
        assertTrue((end.tokenUsage?.total ?: 0) > 0, "expected Gemini stream usage, got ${end.tokenUsage}")
    }

    @Tag("live-cloud-api")
    @Test
    fun `model invokes typed tool through Gemini function calling`() {
        assumeTrue(apiKey != null, "skipping: no Gemini key at .secrets/gemini-key or GEMINI_API_KEY")
        val tool = ToolDef(
            name = "report_number",
            description = "Report the exact integer requested by the user. Arguments: {value: integer}.",
            argsType = ReportNumberArgs::class,
        ) { it }
        val client = GeminiClient(
            apiKey = apiKey!!,
            model = model,
            temperature = 0.0,
            maxTokens = 256,
            tools = listOf(tool),
            toolChoice = ToolChoice.Required,
        )

        val response = skipIfEnvironmental {
            client.chat(listOf(
                LlmMessage("user", """Call report_number with JSON arguments {"value":7}."""),
            ))
        }

        val calls = assertIs<LlmResponse.ToolCalls>(response)
        val call = calls.calls.single()
        assertEquals("report_number", call.name)
        assertEquals(7, (call.arguments["value"] as Number).toInt())
        assertEquals("gemini", calls.tokenUsage?.provider)
    }

    @Tag("live-cloud-api")
    @Test
    fun `full agentic loop with Gemini typed tool returns final answer`() {
        assumeTrue(apiKey != null, "skipping: no Gemini key at .secrets/gemini-key or GEMINI_API_KEY")
        val key = apiKey!!
        val captured = mutableListOf<AddArgs>()

        val a = agent<String, String>("gemini-add") {
            lateinit var add: Tool<AddArgs, AddResult>
            prompt(
                "You are a tool-calling agent. You MUST call add_numbers for arithmetic. " +
                    "After the tool returns, answer with the sum as plain text.",
            )
            model {
                gemini(model)
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

        val out = skipIfEnvironmental { runBlocking { a.invokeSuspend("Use add_numbers to add 17 and 25.") } }

        assertTrue(captured.isNotEmpty(), "Gemini must invoke the typed tool; final answer was '$out'")
        assertEquals(17, captured.first().a)
        assertEquals(25, captured.first().b)
        assertTrue("42" in out, "expected final answer to include 42, got '$out'")
    }

    // Environmental conditions that the adapter correctly surfaces as LlmProviderException but which are
    // NOT code defects — so we SKIP (not fail) the smoke test rather than redden the build on them:
    //  - rate limits / quota (RESOURCE_EXHAUSTED / 429) — free-tier keys throttle.
    //  - geo-block (FAILED_PRECONDITION: "User location is not supported") — Gemini restricts by runner IP
    //    region (#4553); the test runner's geography is not something the framework can fix.
    private fun <T> skipIfEnvironmental(block: () -> T): T = try {
        block()
    } catch (e: LlmProviderException) {
        val m = e.message ?: ""
        when {
            "RESOURCE_EXHAUSTED" in m || "quota" in m || "429" in m -> abort("skipping: Gemini quota / rate limit — $m")
            "FAILED_PRECONDITION" in m && "location is not supported" in m -> abort("skipping: Gemini geo-block — $m")
            else -> throw e
        }
    }

    private fun loadApiKey(): String? {
        val path: Path = Paths.get(".secrets", "gemini-key")
        if (Files.isReadable(path)) {
            val raw = Files.readString(path).trim()
            if (raw.isNotEmpty()) return raw
        }
        return System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
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
