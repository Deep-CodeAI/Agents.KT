package agents_engine.mcp

import agents_engine.core.agent
import agents_engine.generation.Generable
import agents_engine.generation.Guide
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #2483 (under Koog regression epic #2474) — MCP tools/call responses must
 * carry clean text, never Kotlin debug strings like
 * `MyResult(text=Hello, annotations=null)` that leak the executor's
 * internal data class shape.
 *
 * Two contract halves:
 *
 * 1. **String output is the trivial case** — clean text in, clean text out.
 *    Pin so a refactor of [McpServer.mcpToolResult] can't break it.
 * 2. **`@Generable` data class output is the Koog-regression case** —
 *    serializes as JSON, not as `cls(field=value)` Kotlin debug form.
 *    This is the path that the original Koog signal hit (`TextContent(...)`
 *    leaks reaching the LLM through a typed tool result).
 *
 * Non-`@Generable` data class output is intentionally left out of scope
 * here: the current fallback is still `.toString()` (per `toLlmInput` in
 * GenerableSupport.kt), so anyone who registers a non-Generable typed
 * output type will see the debug form. That's a documented limitation,
 * not a regression — Agents.KT recommends `@Generable` for typed
 * boundaries.
 */
class KoogRegressionMcpCleanTextOutputTest {

    @Generable("A search result payload")
    data class SearchPayload(
        @Guide("The body of the result") val text: String,
        @Guide("Source identifier") val source: String,
    )

    private val toStop = mutableListOf<() -> Unit>()

    @AfterTest fun cleanup() { toStop.forEach { runCatching { it() } } }

    private fun callTool(server: McpServer, toolName: String, args: Map<String, Any?> = emptyMap()): String {
        val argsJson = if (args.isEmpty()) "{}" else "{" + args.entries.joinToString(",") {
            "\"${it.key}\":\"${it.value}\""
        } + "}"
        val request = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$toolName","arguments":$argsJson}}"""
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder()
                .uri(URI.create(server.url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode(), "tools/call must return 200; body=${response.body()}")
        return response.body()
    }

    @Test
    fun `String-output tool returns clean text — no kotlin toString decoration`() {
        val a = agent<String, String>("clean") {
            skills {
                skill<String, String>("greet", "Greets") { implementedBy { "Hello world" } }
            }
        }
        val server = McpServer.from(a) { expose("greet"); port = 0 }.start().also {
            toStop.add(it::stop)
        }

        val body = callTool(server, "greet", mapOf("input" to "anything"))

        // The MCP JSON-RPC result must contain a content array with type=text
        // and a text field equal to the executor's return value verbatim.
        assertTrue(
            body.contains(""""type":"text""""),
            "result must declare a text-typed content block: $body",
        )
        assertTrue(
            body.contains(""""text":"Hello world""""),
            "result must carry the clean executor output: $body",
        )
        // The Koog leak shape would be e.g. "Hello world(...)" or a wrapping
        // object name with field-equals syntax — neither must appear.
        assertFalse(
            body.contains("kotlin.String(") || body.contains("String@"),
            "result must not leak Kotlin debug toString shapes: $body",
        )
    }

    @Test
    fun `@Generable output serializes as JSON, not as Kotlin data-class debug toString`() {
        // Koog regression case: the executor returns a typed @Generable; the
        // MCP text content must be the JSON form, not "SearchPayload(text=..., source=...)".
        val a = agent<String, SearchPayload>("typed") {
            skills {
                skill<String, SearchPayload>("search", "Searches") {
                    implementedBy { _ -> SearchPayload(text = "Hello", source = "wiki") }
                }
            }
        }
        val server = McpServer.from(a) { expose("search"); port = 0 }.start().also {
            toStop.add(it::stop)
        }

        val body = callTool(server, "search", mapOf("input" to "anything"))

        // What the leak would look like (the assertion fails today before the fix):
        assertFalse(
            body.contains("SearchPayload(text="),
            "@Generable output must NOT leak the Kotlin data-class toString form (SearchPayload(text=...)); got: $body",
        )
        // What we want instead — the MCP text field carries the JSON form of
        // the @Generable. Inside the JSON-RPC envelope the inner JSON quotes
        // are re-escaped, so e.g. `Hello` shows up as `\"Hello\"`. Match on
        // the unambiguous field-name + value tokens, not the raw chars.
        assertTrue(
            body.contains("""\"text\":\"Hello\"""") &&
                body.contains("""\"source\":\"wiki\""""),
            "expected the typed output rendered as JSON inside the MCP text field; got: $body",
        )
    }
}
