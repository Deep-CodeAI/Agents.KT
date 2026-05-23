package agents_engine.mcp

import agents_engine.core.agent
import agents_engine.generation.LenientJsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerSecurityTest {

    private val toStop = mutableListOf<() -> Unit>()
    private val http = HttpClient.newHttpClient()

    @AfterTest fun cleanup() {
        toStop.forEach { runCatching { it() } }
    }

    private fun twoToolAgent(secretExecuted: AtomicBoolean = AtomicBoolean(false)) =
        agent<String, String>("secure-mcp") {
            skills {
                skill<String, String>("public", "Public information") {
                    implementedBy { input -> "public:$input" }
                }
                skill<String, String>("secret", "Sensitive action") {
                    implementedBy { input ->
                        secretExecuted.set(true)
                        "secret:$input"
                    }
                }
            }
        }

    private fun start(server: McpServer): McpServer =
        server.start().also { toStop.add { it.stop() } }

    private fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseEnvelope(payload: String): Map<String, Any?> =
        LenientJsonParser.parse(payload) as? Map<String, Any?>
            ?: error("not a JSON object: $payload")

    @Test
    fun `bearer auth rejects missing credentials before JSON-RPC dispatch`() {
        val server = start(McpServer.from(twoToolAgent()) {
            expose("public")
            auth = McpServerAuth.RequireBearerToken("secret-token", ClientPrincipal("ci"))
        })

        val response = postJson(
            server.url,
            """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
        )

        assertEquals(401, response.statusCode())
        assertTrue(response.body().contains("Unauthorized", ignoreCase = true), response.body())
    }

    @Test
    fun `origin and host allowlists reject missing or mismatched browser origins`() {
        val server = start(McpServer.from(twoToolAgent()) {
            expose("public")
            allowedHosts = setOf("localhost")
            originAllowlist = setOf("https://allowed.example")
        })
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""

        val missingOrigin = postJson(server.url, ping)
        val badOrigin = postJson(server.url, ping, mapOf("Origin" to "https://evil.example"))
        val allowedOrigin = postJson(server.url, ping, mapOf("Origin" to "https://allowed.example"))

        assertEquals(403, missingOrigin.statusCode(), missingOrigin.body())
        assertEquals(403, badOrigin.statusCode(), badOrigin.body())
        assertEquals(200, allowedOrigin.statusCode(), allowedOrigin.body())
    }

    @Test
    fun `tool policy filters tools-list and hides denied tools-call existence`() {
        val secretExecuted = AtomicBoolean(false)
        val server = start(McpServer.from(twoToolAgent(secretExecuted)) {
            expose("public")
            expose("secret")
            auth = McpServerAuth.RequireBearerTokens(
                mapOf(
                    "low-token" to ClientPrincipal("low"),
                    "admin-token" to ClientPrincipal("admin"),
                ),
            )
            toolPolicy { principal, toolName ->
                principal.id == "admin" || toolName == "public"
            }
        })

        val lowHeaders = mapOf("Authorization" to "Bearer low-token")
        val adminHeaders = mapOf("Authorization" to "Bearer admin-token")

        val lowList = parseEnvelope(
            postJson(server.url, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", lowHeaders).body(),
        )
        val lowTools = ((lowList["result"] as Map<*, *>)["tools"] as List<*>)
            .map { (it as Map<*, *>)["name"] }
        assertEquals(listOf("public"), lowTools)

        val denied = parseEnvelope(
            postJson(
                server.url,
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"secret","arguments":{"input":"x"}}}""",
                lowHeaders,
            ).body(),
        )
        val deniedError = denied["error"] as? Map<*, *>
        assertNotNull(deniedError, "policy deny should be a JSON-RPC error: $denied")
        assertEquals(-32601, (deniedError["code"] as Number).toInt())
        assertFalse(denied.toString().contains("secret"), "denial must not leak denied tool name: $denied")
        assertFalse(secretExecuted.get(), "denied tool must not execute")

        val allowed = parseEnvelope(
            postJson(
                server.url,
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"secret","arguments":{"input":"x"}}}""",
                adminHeaders,
            ).body(),
        )
        assertNull(allowed["error"], "admin should be allowed: $allowed")
        assertTrue(allowed.toString().contains("secret:x"), "admin result should include tool output: $allowed")
    }

    @Test
    fun `snapshot and initialize capabilities are filtered for each principal`() {
        val server = start(McpServer.from(twoToolAgent()) {
            expose("public")
            expose("secret")
            auth = McpServerAuth.RequireBearerTokens(
                mapOf(
                    "low-token" to ClientPrincipal("low"),
                    "blocked-token" to ClientPrincipal("blocked"),
                ),
            )
            toolPolicy { principal, toolName ->
                principal.id == "low" && toolName == "public"
            }
        })

        val lowSnapshot = server.snapshotFor(ClientPrincipal("low"))
        assertEquals(listOf("public"), lowSnapshot.tools?.map { it.name })
        assertNotNull(lowSnapshot.capabilities.tools)

        val blockedSnapshot = server.snapshotFor(ClientPrincipal("blocked"))
        assertNull(blockedSnapshot.tools)
        assertNull(blockedSnapshot.capabilities.tools)

        val init = parseEnvelope(
            postJson(
                server.url,
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$MCP_PROTOCOL_VERSION","capabilities":{},"clientInfo":{"name":"blocked","version":"0"}}}""",
                mapOf("Authorization" to "Bearer blocked-token"),
            ).body(),
        )
        val capabilities = ((init["result"] as Map<*, *>)["capabilities"] as Map<*, *>)
        assertFalse("tools" in capabilities, "blocked principal should not negotiate tools: $capabilities")
    }
}
