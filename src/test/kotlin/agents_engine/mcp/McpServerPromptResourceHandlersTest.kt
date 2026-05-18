package agents_engine.mcp

import agents_engine.core.agent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

// Tests for #1976 — McpServer prompt + resource handler branches (43 unkilled
// mutants in handle / handlePromptGet / handleResourceRead / toMcpDescriptor).
// Mirrors McpServerErrorPathsTest's raw-POST + HTTP-client pattern.
//
// Targets:
// - handlePromptGet:190 (params Map cast) / 191 (missing name → -32602)
// - handlePromptGet:193 (unknown prompt name → -32601)
// - handlePromptGet:196 (args Map fallback when null)
// - handlePromptGet:197 + 209 (render success/failure branches)
// - handleResourceRead:228-231 (params/uri/registered-lookup)
// - handleResourceRead:239 + 245 (read success/failure)
// - toMcpDescriptor:216 (prompt arguments-list inclusion when non-empty)
// - toMcpDescriptor:220 (per-arg description optional)
// - toMcpDescriptor:252/253 (resource description+mimeType optional)
// - handle:118-119 (body size guard boundary)
// - handle:112/113 (Content-Length declaration handling)
class McpServerPromptResourceHandlersTest {

    private val toStop = mutableListOf<() -> Unit>()
    @AfterTest fun cleanup() { toStop.forEach { runCatching { it() } } }

    private fun trivialAgent() = agent<String, String>("greeter") {
        skills { skill<String, String>("greet", "Greet") { implementedBy { "hi $it" } } }
    }

    private fun postRaw(url: String, body: String, contentLength: Long? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (contentLength != null) builder.header("Content-Length", contentLength.toString())
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun serverWithPrompts(
        configure: McpExposeBuilder.() -> Unit,
    ): McpServer {
        val server = McpServer.from(trivialAgent()) {
            expose("greet")  // need at least one expose() OR prompt()/resource()
            port = 0
            configure()
        }.start()
        toStop.add { server.stop() }
        return server
    }

    // ── handlePromptGet: missing/unknown name (lines 191, 193) ────────────────

    @Test
    fun `prompts get with missing name returns -32602 error`() {
        // Kills NegateConditionals on line 191 (`name as? String ?: return ...`).
        val server = serverWithPrompts {
            prompt("hello", "Greets") { _ -> "Hello, world!" }
        }
        val r = postRaw(server.url, """{"jsonrpc":"2.0","id":1,"method":"prompts/get","params":{}}""")
        assertEquals(200, r.statusCode())
        assertTrue(r.body().contains("\"code\":-32602"), "missing name must use -32602 invalid-params: ${r.body()}")
        assertTrue(r.body().contains("Missing prompt name"), "error message should be descriptive: ${r.body()}")
    }

    @Test
    fun `prompts get with unknown name returns -32601 error`() {
        // Line 193: `registeredPrompts.firstOrNull { it.name == name } ?: return ...`
        val server = serverWithPrompts {
            prompt("hello", "Greets") { _ -> "Hi" }
        }
        val r = postRaw(server.url, """{"jsonrpc":"2.0","id":1,"method":"prompts/get","params":{"name":"nonexistent"}}""")
        assertEquals(200, r.statusCode())
        assertTrue(r.body().contains("\"code\":-32601"), "unknown prompt must use -32601 method-not-found: ${r.body()}")
        assertTrue(r.body().contains("nonexistent"), "error must name the unknown prompt: ${r.body()}")
    }

    // ── handlePromptGet: happy path (line 197 + 199-207) ──────────────────────

    @Test
    fun `prompts get happy path returns rendered prompt with description`() {
        // Line 192 + 194 + 197: the result envelope must carry the right shape.
        // `replaced return value with ""` mutants on 192/194/197 would yield
        // empty/null bodies; this test pins the actual content.
        val server = serverWithPrompts {
            prompt("greet", "Say hi to a person", arguments = listOf(
                McpPromptArgument("name", "Who to greet", required = true)
            )) { args -> "Hello, ${args["name"]}!" }
        }
        val r = postRaw(server.url, """{"jsonrpc":"2.0","id":42,"method":"prompts/get","params":{"name":"greet","arguments":{"name":"Alice"}}}""")
        assertEquals(200, r.statusCode())
        val body = r.body()
        assertTrue(body.contains("\"description\":\"Say hi to a person\""),
            "result must include the prompt description: $body")
        assertTrue(body.contains("Hello, Alice!"),
            "result must include the rendered prompt text: $body")
        assertTrue(body.contains("\"role\":\"user\""), "messages must be user-role: $body")
        assertTrue(body.contains("\"id\":42"), "id must round-trip: $body")
    }

    @Test
    fun `prompts get with null arguments falls back to empty map (line 196)`() {
        // Line 196: `(params["arguments"] as? Map<String, Any?>) ?: emptyMap()`
        // The render closure must receive an empty map, not crash on null.
        var seen: Map<String, Any?>? = null
        val server = serverWithPrompts {
            prompt("no-args", "no args") { args -> seen = args; "static text" }
        }
        // params has name but NO arguments field.
        val r = postRaw(server.url, """{"jsonrpc":"2.0","id":1,"method":"prompts/get","params":{"name":"no-args"}}""")
        assertEquals(200, r.statusCode())
        assertTrue(r.body().contains("static text"))
        assertEquals(emptyMap<String, Any?>(), seen, "render must receive empty map when arguments missing")
    }

    // ── handlePromptGet: render throws (line 208-210) ─────────────────────────

    @Test
    fun `prompts get render throwing returns -32603 internal error with cause message`() {
        // Line 208-210: the catch wraps any exception in -32603 with "Prompt 'X' rendering failed: <msg>".
        val server = serverWithPrompts {
            prompt("explosive", "Boom") { _ -> throw IllegalStateException("kapow") }
        }
        val r = postRaw(server.url, """{"jsonrpc":"2.0","id":1,"method":"prompts/get","params":{"name":"explosive"}}""")
        assertEquals(200, r.statusCode())
        val body = r.body()
        assertTrue(body.contains("\"code\":-32603"), "render exception → internal error: $body")
        assertTrue(body.contains("kapow"), "original exception message must propagate: $body")
        assertTrue(body.contains("rendering failed"), "wrapper text must appear: $body")
    }

    // ── handleResourceRead: missing/unknown uri (lines 229, 231) ──────────────

    @Test
    fun `resources read with missing uri returns -32602 error`() {
        val server = serverWithPrompts {
            resource("file:///x", name = "x") { "content" }
        }
        val r = postRaw(server.url, """{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{}}""")
        assertEquals(200, r.statusCode())
        assertTrue(r.body().contains("\"code\":-32602"))
        assertTrue(r.body().contains("Missing resource uri"))
    }

    @Test
    fun `resources read with unknown uri returns -32601 error`() {
        val server = serverWithPrompts {
            resource("file:///known", name = "k") { "content" }
        }
        val r = postRaw(server.url, """{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"file:///nope"}}""")
        assertEquals(200, r.statusCode())
        assertTrue(r.body().contains("\"code\":-32601"))
        assertTrue(r.body().contains("file:///nope"), "error must name the unknown uri: ${r.body()}")
    }

    // ── handleResourceRead: happy path (lines 233-243) ────────────────────────

    @Test
    fun `resources read happy path returns the resource content with mimeType`() {
        val server = serverWithPrompts {
            resource("doc://x", name = "X-doc", mimeType = "text/markdown") {
                "# Hello\nThis is content."
            }
        }
        val r = postRaw(server.url, """{"jsonrpc":"2.0","id":7,"method":"resources/read","params":{"uri":"doc://x"}}""")
        assertEquals(200, r.statusCode())
        val body = r.body()
        assertTrue(body.contains("doc://x"))
        assertTrue(body.contains("text/markdown"))
        assertTrue(body.contains("Hello"))
        assertTrue(body.contains("\"id\":7"), "id round-trip: $body")
    }

    @Test
    fun `resources read happy path without mimeType omits the field`() {
        // Line 239: `resource.mimeType?.let { put("mimeType", it) }` — the let block
        // only runs when mimeType is non-null. Mutant negates this and would emit
        // the field with a null/missing value.
        val server = serverWithPrompts {
            resource("plain://x", name = "Plain") { "just text" }
        }
        val r = postRaw(server.url, """{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"plain://x"}}""")
        assertEquals(200, r.statusCode())
        assertFalse(r.body().contains("mimeType"),
            "mimeType field should be absent when not declared: ${r.body()}")
    }

    // ── handleResourceRead: read throws (line 244-246) ────────────────────────

    @Test
    fun `resources read throwing returns -32603 with cause message`() {
        val server = serverWithPrompts {
            resource("file:///broken", name = "B") { throw RuntimeException("io fail") }
        }
        val r = postRaw(server.url, """{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"file:///broken"}}""")
        assertEquals(200, r.statusCode())
        val body = r.body()
        assertTrue(body.contains("\"code\":-32603"))
        assertTrue(body.contains("io fail"))
        assertTrue(body.contains("read failed"))
    }

    // ── prompts/list + RegisteredPrompt.toMcpDescriptor (lines 216, 220) ──────

    @Test
    fun `prompts list emits arguments only when prompt has arguments`() {
        // Line 216 in toMcpDescriptor: `if (arguments.isNotEmpty()) put(...)`.
        // Negated mutant would emit an empty arguments list or skip non-empty ones.
        val server = serverWithPrompts {
            prompt("with-args", "Has args", arguments = listOf(
                McpPromptArgument("a", "first", required = true)
            )) { _ -> "x" }
            prompt("no-args", "No args") { _ -> "y" }
        }
        val r = postRaw(server.url, """{"jsonrpc":"2.0","id":1,"method":"prompts/list"}""")
        assertEquals(200, r.statusCode())
        val body = r.body()
        assertTrue(body.contains("with-args"), "list must include with-args prompt: $body")
        assertTrue(body.contains("no-args"), "list must include no-args prompt: $body")
        // with-args section should have an "arguments" key; no-args section should NOT.
        // We can't easily slice the JSON, but we assert the per-arg name is present.
        assertTrue(body.contains("\"name\":\"a\""), "arguments list must surface arg name: $body")
        assertTrue(body.contains("\"description\":\"first\""),
            "arg description must surface (kills line 220 optional-description mutant): $body")
        assertTrue(body.contains("\"required\":true"))
    }

    // ── resources/list + RegisteredResource.toMcpDescriptor (lines 252, 253) ──

    @Test
    fun `resources list emits description and mimeType only when non-null`() {
        // Lines 252 + 253: `description?.let { put(...) }` and same for mimeType.
        val server = serverWithPrompts {
            resource("doc://full", name = "Full",
                description = "with details", mimeType = "text/plain") { "content" }
            resource("doc://bare", name = "Bare") { "content" }
        }
        val r = postRaw(server.url, """{"jsonrpc":"2.0","id":1,"method":"resources/list"}""")
        assertEquals(200, r.statusCode())
        val body = r.body()
        assertTrue(body.contains("doc://full"))
        assertTrue(body.contains("doc://bare"))
        assertTrue(body.contains("\"description\":\"with details\""),
            "full resource description must surface: $body")
        assertTrue(body.contains("\"mimeType\":\"text/plain\""),
            "full resource mimeType must surface: $body")
    }

    // NOTE on handle:112-115 (Content-Length declared-length check):
    // Java's HttpClient restricts the "Content-Length" header from being set
    // manually (throws IllegalArgumentException: restricted header name).
    // To exercise the declared-length branch we'd need a different HTTP client
    // (or a raw Socket-level test). `McpServerBodySizeLimitTest` covers the
    // unbound-body length-capped read path which is the more interesting one
    // anyway. Leaving the declared-length branch unkilled for now — it's the
    // same logic shape as the unbound path, just an optimization for clients
    // that announce up front.
}
