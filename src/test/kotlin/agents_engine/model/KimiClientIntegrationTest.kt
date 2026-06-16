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
 * #2697 — live Kimi (Moonshot AI) Chat Completions smoke test. Tagged
 * `live-cloud-api` so it runs in the default `:test` task when a key is
 * present and skips cleanly otherwise (parity with the DeepSeek / OpenAI
 * / Anthropic live integration tests).
 *
 * Key loading:
 * - reads `<repo-root>/.secrets/kimi-key` (gitignored)
 * - falls back to `KIMI_API_KEY`
 * - skips via JUnit `Assumptions` if neither is present
 *
 * Note on tag: gated `live-llm` (excluded from default `:test`) rather than
 * the DeepSeek-style `live-cloud-api`. Reason: as of the initial branch
 * commit, the local `.secrets/kimi-key` returns `Invalid Authentication`
 * from `api.moonshot.cn` (confirmed via direct `curl` — code paths are
 * independent). Flip to `live-cloud-api` once the key validates so this
 * suite gains parity with DeepSeek's default-run live coverage.
 */
class KimiClientIntegrationTest {

    private val apiKey: String? = loadApiKey()
    private val model: String = System.getenv("KIMI_TEST_MODEL") ?: "moonshot-v1-8k"
    // #4511 — point at the right Moonshot region (.cn China / .ai International) for the key.
    // Local `.secrets/kimi-key` is an International key, so set KIMI_BASE_URL=https://api.moonshot.ai.
    private val baseUrl: String = System.getenv("KIMI_BASE_URL") ?: KimiClient.DEFAULT_BASE_URL

    // Moonshot's two-platform split (.cn/.ai) and free-tier quota make auth/rate-limit an ENVIRONMENTAL
    // condition, not a framework defect — skip (don't fail), mirroring the Gemini geo-block guard. With a key
    // for the platform `baseUrl` points at, these run; otherwise they skip cleanly instead of reddening CI.
    private inline fun <T> skipIfEnvironmental(block: () -> T): T = try {
        block()
    } catch (e: LlmProviderException) {
        val m = e.message ?: ""
        val markers = listOf("uthentication", "RESOURCE_EXHAUSTED", "quota", "429")
        if (markers.any { it in m }) {
            abort("skipping: Kimi auth/quota (environmental) — $m")
        } else {
            throw e
        }
    }

    @Tag("live-llm")
    @Test
    fun `returns text response for simple prompt`() {
        assumeTrue(apiKey != null, "skipping: no Kimi key at .secrets/kimi-key or KIMI_API_KEY")
        val client = KimiClient(apiKey = apiKey!!, model = model, temperature = 0.0, maxTokens = 64, baseUrl = baseUrl)

        val response = skipIfEnvironmental {
            client.chat(listOf(LlmMessage("user", "Reply with exactly the word: pong")))
        }

        val text = assertIs<LlmResponse.Text>(response)
        assertTrue(text.content.isNotBlank(), "expected non-blank text, got '${text.content}'")
        assertTrue(
            (text.tokenUsage?.total ?: 0) > 0,
            "expected positive token usage, got ${text.tokenUsage}",
        )
        assertEquals("kimi", text.tokenUsage?.provider)
    }

    @Tag("live-llm")
    @Test
    fun `streaming response emits text deltas and Kimi usage`() = runBlocking {
        assumeTrue(apiKey != null, "skipping: no Kimi key at .secrets/kimi-key or KIMI_API_KEY")
        val client = KimiClient(apiKey = apiKey!!, model = model, temperature = 0.0, maxTokens = 64, baseUrl = baseUrl)

        val chunks = skipIfEnvironmental {
            client.chatStream(listOf(
                LlmMessage("user", "Count from 1 to 5 separated by spaces. Output only the numbers."),
            )).toList()
        }

        assertTrue(chunks.isNotEmpty(), "expected streaming chunks")
        val end = assertIs<LlmChunk.End>(chunks.last())
        val text = chunks.dropLast(1)
            .filterIsInstance<LlmChunk.TextDelta>()
            .joinToString("") { it.text }
        assertTrue("1" in text && "5" in text, "expected count output, got '$text'")
        assertEquals("kimi", end.tokenUsage?.provider)
        assertTrue((end.tokenUsage?.total ?: 0) > 0, "expected Kimi stream usage, got ${end.tokenUsage}")
    }

    @Tag("live-llm")
    @Test
    fun `model invokes typed tool through Kimi function calling`() {
        assumeTrue(apiKey != null, "skipping: no Kimi key at .secrets/kimi-key or KIMI_API_KEY")
        val tool = ToolDef(
            name = "report_number",
            description = "Report the exact integer requested by the user. Arguments: {value: integer}.",
            argsType = ReportNumberArgs::class,
        ) { it }
        val client = KimiClient(
            apiKey = apiKey!!,
            model = model,
            temperature = 0.0,
            maxTokens = 128,
            tools = listOf(tool),
            baseUrl = baseUrl,
        )

        val response = skipIfEnvironmental {
            client.chat(
                listOf(
                    LlmMessage(
                        "system",
                        "You are a tool-calling assistant. Always call the available tool; do not answer in text.",
                    ),
                    LlmMessage("user", """Call report_number with JSON arguments {"value":7}."""),
                ),
            )
        }

        val calls = assertIs<LlmResponse.ToolCalls>(response)
        val call = calls.calls.single()
        assertEquals("report_number", call.name)
        assertEquals(7, (call.arguments["value"] as Number).toInt())
        assertEquals("kimi", calls.tokenUsage?.provider)
    }

    @Tag("live-llm")
    @Test
    fun `full agentic loop with Kimi typed tool returns final answer`() {
        assumeTrue(apiKey != null, "skipping: no Kimi key at .secrets/kimi-key or KIMI_API_KEY")
        val key = apiKey!!
        val captured = mutableListOf<AddArgs>()

        val a = agent<String, String>("kimi-add") {
            lateinit var add: Tool<AddArgs, AddResult>
            prompt(
                "You are a tool-calling agent. You MUST call add_numbers for arithmetic. " +
                    "After the tool returns, answer with the sum as plain text.",
            )
            model {
                kimi(model)
                apiKey = key
                kimiBaseUrl = baseUrl // honor KIMI_BASE_URL like the direct-client tests (else always hits .cn)
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

        assertTrue(captured.isNotEmpty(), "Kimi must invoke the typed tool; final answer was '$out'")
        assertEquals(17, captured.first().a)
        assertEquals(25, captured.first().b)
        assertTrue("42" in out, "expected final answer to include 42, got '$out'")
    }

    private fun loadApiKey(): String? {
        val path: Path = Paths.get(".secrets", "kimi-key")
        if (Files.isReadable(path)) {
            val raw = Files.readString(path).trim()
            if (raw.isNotEmpty()) return raw
        }
        return System.getenv("KIMI_API_KEY")?.takeIf { it.isNotBlank() }
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
