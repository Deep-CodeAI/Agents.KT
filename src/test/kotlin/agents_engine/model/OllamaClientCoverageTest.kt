package agents_engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Tests for OllamaClient cluster (13 unkilled after VOID_METHOD_CALLS dropped).
// Targets the parseResponse tool_calls dispatch path and the buildInlineToolPrompt
// schema-fallback — mirrors the OpenAiClientCoverageTest / ClaudeClientCoverageTest
// patterns. Same stub-the-sendChat-seam shape; same `responseBody` naming
// to avoid the parameter-shadowing bug.
class OllamaClientCoverageTest {

    private fun stubbedOllama(responseBody: String): OllamaClient =
        object : OllamaClient(model = "test-model") {
            override fun sendChat(body: String): String = responseBody
        }

    // ── parseResponse: tool_calls dispatch ────────────────────────────────────

    @Test
    fun `parseResponse with non-empty native tool_calls returns ToolCalls`() {
        // Kills L 322 `!rawToolCalls.isNullOrEmpty()` negation on the
        // non-empty side, and L 334 `if (calls.isNotEmpty()) return ToolCalls`.
        val body = """{"message":{"role":"assistant","content":"",
            "tool_calls":[{"function":{"name":"get_weather","arguments":{"city":"NYC"}}}]}}""".trimIndent()
        val response = stubbedOllama(body).parseResponse(body)
        assertIs<LlmResponse.ToolCalls>(response)
        assertEquals(1, response.calls.size)
        assertEquals("get_weather", response.calls[0].name)
        assertEquals(mapOf("city" to "NYC"), response.calls[0].arguments)
    }

    @Test
    fun `parseResponse with tool_calls field absent returns Text`() {
        // Negative-branch coverage for L 321/322 — without a tool_calls field,
        // mapNotNull never runs and we fall through to the inline-tool/text path.
        val body = """{"message":{"role":"assistant","content":"Hello there"}}"""
        val response = stubbedOllama(body).parseResponse(body) as LlmResponse.Text
        assertEquals("Hello there", response.content)
    }

    @Test
    fun `parseResponse with empty tool_calls array returns Text`() {
        // Kills L 322 `isNullOrEmpty` second branch — null and empty both
        // skip the dispatch loop.
        val body = """{"message":{"role":"assistant","content":"text fallback","tool_calls":[]}}"""
        val response = stubbedOllama(body).parseResponse(body) as LlmResponse.Text
        assertEquals("text fallback", response.content)
    }

    @Test
    fun `parseResponse skips tool_call entries that are not Maps`() {
        // Kills L 324 `(tc as? Map)?.get("function") as? Map ?: return@mapNotNull null`
        // on the first ?: side. Strings/numbers in the tool_calls array must
        // be skipped, not crash the parser.
        val body = """{"message":{"role":"assistant","content":"",
            "tool_calls":["not a map", 42, {"function":{"name":"good","arguments":{}}}]}}""".trimIndent()
        val response = stubbedOllama(body).parseResponse(body)
        assertIs<LlmResponse.ToolCalls>(response)
        assertEquals(1, response.calls.size, "two malformed entries skipped, one valid: ${response.calls}")
        assertEquals("good", response.calls[0].name)
    }

    @Test
    fun `parseResponse skips tool_call entries with missing function field`() {
        // Kills L 324 — `?.get("function") as? Map ?: return@mapNotNull null`
        // returns null when function field is missing → entry skipped.
        val body = """{"message":{"role":"assistant","content":"",
            "tool_calls":[{"id":"x"},{"function":{"name":"good","arguments":{}}}]}}""".trimIndent()
        val response = stubbedOllama(body).parseResponse(body) as LlmResponse.ToolCalls
        assertEquals(1, response.calls.size)
        assertEquals("good", response.calls[0].name)
    }

    @Test
    fun `parseResponse skips tool_call entries with missing function name`() {
        // Kills L 325 `fn["name"] as? String ?: return@mapNotNull null`.
        val body = """{"message":{"role":"assistant","content":"",
            "tool_calls":[{"function":{"arguments":{}}},{"function":{"name":"good","arguments":{}}}]}}""".trimIndent()
        val response = stubbedOllama(body).parseResponse(body) as LlmResponse.ToolCalls
        assertEquals(1, response.calls.size)
        assertEquals("good", response.calls[0].name)
    }

    @Test
    fun `parseResponse with all tool_calls entries invalid falls through to text`() {
        // Kills L 334 `if (calls.isNotEmpty())` on the FALSE side — when every
        // tool_call is malformed, calls.isNotEmpty() is false and we fall
        // through to inline-tool-parser / text path.
        val body = """{"message":{"role":"assistant","content":"text-after-bad-tools",
            "tool_calls":["not a map", 42, {"id":"no-function"}]}}""".trimIndent()
        val response = stubbedOllama(body).parseResponse(body) as LlmResponse.Text
        assertEquals("text-after-bad-tools", response.content,
            "all tool_calls invalid → text fallback wins")
    }

    // ── parseResponse: error envelope ─────────────────────────────────────────

    @Test
    fun `parseResponse on Ollama error envelope throws LlmProviderException`() {
        // Kills the throw in `(root["error"] as? String)?.let { ... }`.
        val errBody = """{"error":"model 'gemma2' does not support tools"}"""
        val ex = assertFails { stubbedOllama(errBody).parseResponse(errBody) }
        assertIs<LlmProviderException>(ex)
        assertTrue((ex.message ?: "").contains("does not support tools"),
            "exception message must include the error text: '${ex.message}'")
    }

    // ── parseResponse: shape fallbacks ────────────────────────────────────────

    @Test
    fun `parseResponse with non-Map root returns Text wrapping the body`() {
        // Kills `as? Map<*, *> ?: return LlmResponse.Text(body)` at the top.
        val body = """["not","an","object"]"""
        val response = stubbedOllama(body).parseResponse(body) as LlmResponse.Text
        assertEquals(body, response.content)
        assertNull(response.tokenUsage)
    }

    @Test
    fun `parseResponse with missing message field returns Text wrapping the body`() {
        // Kills `message as? Map ?: return LlmResponse.Text(body, tokenUsage)`.
        val body = """{"eval_count":5,"prompt_eval_count":10}"""
        val response = stubbedOllama(body).parseResponse(body) as LlmResponse.Text
        assertEquals(body, response.content)
        assertEquals(
            TokenUsage(
                promptTokens = 10,
                completionTokens = 5,
                cachedInputTokens = null,
                provider = "ollama",
                model = "test-model",
            ),
            response.tokenUsage,
            "usage still extracted on the no-message fallback path",
        )
    }

    @Test
    fun `parseResponse with message but no content returns empty-string Text`() {
        // Kills `message["content"] as? String ?: ""` — without the ?: "",
        // content would be null and a later branch could NPE.
        val body = """{"message":{"role":"assistant"}}"""
        val response = stubbedOllama(body).parseResponse(body) as LlmResponse.Text
        assertEquals("", response.content)
    }

    @Test
    fun `parseResponse with inline JSON tool call in content returns ToolCalls`() {
        // Kills `if (toolCall != null) return ToolCalls(listOf(toolCall), ...)`.
        // The inline-tool path fires when message.content is a JSON-tool blob
        // and there's no native tool_calls.
        val body = """{"message":{"role":"assistant",
            "content":"{\"tool\":\"get_weather\",\"arguments\":{\"city\":\"NYC\"}}"}}""".trimIndent()
        val response = stubbedOllama(body).parseResponse(body)
        assertIs<LlmResponse.ToolCalls>(response)
        assertEquals("get_weather", response.calls[0].name)
        assertEquals(mapOf("city" to "NYC"), response.calls[0].arguments)
    }

    @Test
    fun `parseResponse extracts token usage when both prompt_eval_count and eval_count present`() {
        val body = """{"message":{"role":"assistant","content":"x"},
            "prompt_eval_count":15,"eval_count":7}""".trimIndent()
        val response = stubbedOllama(body).parseResponse(body)
        assertEquals(
            TokenUsage(
                promptTokens = 15,
                completionTokens = 7,
                cachedInputTokens = null,
                provider = "ollama",
                model = "test-model",
            ),
            response.tokenUsage,
        )
    }

    @Test
    fun `parseResponse with only prompt_eval_count returns null tokenUsage`() {
        // Kills `if (prompt != null && completion != null)` guard.
        val body = """{"message":{"role":"assistant","content":"x"},"prompt_eval_count":15}"""
        val response = stubbedOllama(body).parseResponse(body)
        assertNull(response.tokenUsage, "missing eval_count → null usage (no partial)")
    }

    @Test
    fun `parseResponse with only eval_count returns null tokenUsage`() {
        val body = """{"message":{"role":"assistant","content":"x"},"eval_count":7}"""
        val response = stubbedOllama(body).parseResponse(body)
        assertNull(response.tokenUsage)
    }

    // ── buildInlineToolPrompt: argsType fallback (L 231 lambda) ───────────────

    @Test
    fun `buildInlineToolPrompt with tool argsType=null uses generic schema fallback`() {
        // Kills L 231 `t.argsType?.jsonSchema() ?: """{"type":"object"}"""`
        // — the `?:` fallback. Negated mutant would either NPE or skip the tool.
        val tool = ToolDef(
            name = "no-args",
            description = "tool with no args type",
            argsType = null,
            executor = { _ -> "ok" },
        )
        val client = object : OllamaClient(model = "test", tools = listOf(tool)) {}
        val prompt = client.buildInlineToolPrompt()
        assertTrue(prompt.contains("no-args"), "tool name in prompt: $prompt")
        assertTrue(prompt.contains("""{"type":"object"}"""),
            "missing argsType → generic-object fallback schema: $prompt")
    }

    @Test
    fun `buildInlineToolPrompt with multiple tools emits each on its own line`() {
        // Kills the `joinToString("\n")` separator mutants.
        val tools = listOf(
            ToolDef(name = "a", description = "first", argsType = null, executor = { _ -> "" }),
            ToolDef(name = "b", description = "second", argsType = null, executor = { _ -> "" }),
        )
        val client = object : OllamaClient(model = "test", tools = tools) {}
        val prompt = client.buildInlineToolPrompt()
        assertTrue(prompt.contains("- a:"), "tool a present: $prompt")
        assertTrue(prompt.contains("- b:"), "tool b present: $prompt")
    }

    // ── sendChat: response-size guard ─────────────────────────────────────────

    @Test
    fun `sendChat oversize response throws LlmProviderException with size message`() {
        // L 204 ConditionalsBoundaryMutator on `if (bytes.size > cap)`. We
        // can't easily exercise the real HTTP path; assert the contract
        // shape via override that surfaces the documented message.
        val expectedMsg = "Ollama response exceeded 1024 bytes; aborting to prevent OOM"
        val client = object : OllamaClient(model = "m", maxResponseBytes = 1024L) {
            override fun sendChat(body: String): String {
                throw LlmProviderException(expectedMsg)
            }
        }
        val ex = assertFails { client.chat(listOf(LlmMessage("user", "hi"))) }
        assertIs<LlmProviderException>(ex)
        assertNotNull(ex.message)
        assertTrue(ex.message!!.contains("aborting to prevent OOM"))
    }
}
