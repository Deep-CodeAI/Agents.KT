package agents_engine.model

import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #2379 — shared wire-format fixture helper for the built-in tool schema
 * tests (`memory_*`, `forum_return`, swarm delegate).
 *
 * The acceptance criteria require fixtures for *each* of the three provider
 * clients, so this renders the tool-defs JSON on all three and lets the
 * per-tool tests assert against every provider at once. `buildRequestJson`
 * is `internal`, so this support file lives in `agents_engine.model`
 * alongside the clients; the test classes in other packages import it.
 */
object BuiltInToolWireSchema {

    /** Render each provider client's request body (with [tools]) keyed by provider name. */
    fun bodies(tools: List<ToolDef>): Map<String, String> = mapOf(
        "Ollama" to object : OllamaClient(model = "test", tools = tools) {}.buildRequestJson(emptyList()),
        "OpenAI" to object : OpenAiClient(apiKey = "test", model = "test", tools = tools) {}.buildRequestJson(emptyList()),
        "Claude" to object : ClaudeClient(apiKey = "test", model = "test", tools = tools) {}.buildRequestJson(emptyList()),
    )

    /** Assert no provider falls through to the legacy `additionalProperties:true` fallback. */
    fun assertNoPermissiveFallback(tools: List<ToolDef>) {
        bodies(tools).forEach { (provider, body) ->
            assertFalse(
                "\"additionalProperties\":true" in body.filterNot { it.isWhitespace() },
                "$provider must not emit the permissive empty-properties fallback: $body",
            )
        }
    }

    /** Assert [substring] appears in every provider's rendered body. */
    fun assertAllContain(tools: List<ToolDef>, substring: String) {
        bodies(tools).forEach { (provider, body) ->
            assertTrue(
                substring in body.filterNot { it.isWhitespace() },
                "$provider body should contain '$substring': $body",
            )
        }
    }
}
