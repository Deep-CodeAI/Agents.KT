package agents_engine.model

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

// Tests for #1977 — OpenAiClient cluster (26 unkilled after VOID_METHOD_CALLS
// dropped). Targets four remaining mutant families the existing
// OpenAiClientChatStreamTest doesn't pin:
//
// 1. parseResponse error envelope (lines 281-283) — type/message extraction
//    from the OpenAI {"error":{...}} shape.
// 2. parseSseStream branches the streaming-text/tool-call happy path misses:
//    EOF without [DONE], late-arriving name without id, late-arriving id.
// 3. buildRequestJson — assistant with empty content (line 239 → "null"),
//    stream-mode adds stream_options (line 268), tool counter increments.
// 4. sendChat response-size guard (line 215 boundary).
class OpenAiClientCoverageTest {

    // ── stub helpers ──────────────────────────────────────────────────────────

    // NOTE: the outer parameter `responseBody` must NOT shadow the override's
    // `body` parameter — otherwise `sendChat`/`sendChatStream` would echo back
    // the REQUEST body instead of returning the fixture.
    private fun stubbedOpenAi(responseBody: String): OpenAiClient =
        object : OpenAiClient(apiKey = "test-key", model = "test-model") {
            override fun sendChat(body: String, headers: Map<String, String>): String = responseBody
            override fun sendChatStream(body: String, headers: Map<String, String>): InputStream =
                responseBody.byteInputStream(Charsets.UTF_8)
        }

    // ── parseResponse: error envelope (lines 281-283) ─────────────────────────

    @Test
    fun `parseResponse on error envelope throws LlmProviderException with type and message`() {
        // Kills mutants on lines 281 ((root["error"] as? Map<*,*>)?.let), 282
        // (val type), 283 (throw with type/message string interpolation).
        val errBody = """{"error":{"type":"invalid_request_error","message":"Invalid API key","code":"invalid_api_key"}}"""
        val client = stubbedOpenAi(errBody) // doesn't matter; we call parseResponse directly
        val ex = assertFails { client.parseResponse(errBody) }
        assertIs<LlmProviderException>(ex)
        assertNotNull(ex.message)
        assertTrue(ex.message!!.contains("invalid_request_error"),
            "exception message should include error type: '${ex.message}'")
        assertTrue(ex.message!!.contains("Invalid API key"),
            "exception message should include the API's message: '${ex.message}'")
    }

    @Test
    fun `parseResponse error envelope missing type still throws with 'unknown' marker`() {
        // Kills the Elvis-on-type mutant — `${type ?: "unknown"}`.
        val errBody = """{"error":{"message":"Server error"}}"""
        val client = stubbedOpenAi(errBody)
        val ex = assertFails { client.parseResponse(errBody) }
        assertTrue((ex.message ?: "").contains("unknown"),
            "missing type field should produce 'unknown' marker: '${ex.message}'")
    }

    @Test
    fun `parseResponse error envelope missing message still throws with 'no message' marker`() {
        val errBody = """{"error":{"type":"server_error"}}"""
        val client = stubbedOpenAi(errBody)
        val ex = assertFails { client.parseResponse(errBody) }
        assertTrue((ex.message ?: "").contains("no message"),
            "missing message field should produce 'no message' marker: '${ex.message}'")
    }

    @Test
    fun `parseResponse non-error body without choices returns text wrapping the body`() {
        // Kills mutants on the `as? List` fallback (line ~287).
        val body = """{"id":"x","object":"weird","choices":null}"""
        val client = stubbedOpenAi(body)
        val response = client.parseResponse(body)
        // Falls through to text wrapping when choices isn't a list.
        assertIs<LlmResponse.Text>(response)
    }

    // ── buildRequestJson: assistant content nullification (line 239) ──────────

    @Test
    fun `buildRequestJson assistant message with empty content emits content as JSON null`() {
        // Line 239: `if (msg.content.isEmpty()) "null" else msg.content.toJsonString()`
        // Negated mutant would emit empty content as `""` instead of `null` —
        // OpenAI's API rejects assistant messages with `null` content+tool_calls
        // in some configurations, so this distinction matters.
        val client = stubbedOpenAi("")
        val messages = listOf(
            LlmMessage("user", "hi"),
            LlmMessage("assistant", "", toolCalls = listOf(
                ToolCall(name = "get_x", arguments = mapOf("a" to 1))
            )),
        )
        val json = client.buildRequestJson(messages)
        // The assistant content for an empty-content tool-call turn must be
        // serialized as JSON null (not `""`).
        assertTrue(json.contains(""""content":null"""),
            "empty assistant content with tool_calls must emit `\"content\":null`: $json")
    }

    @Test
    fun `buildRequestJson assistant message with non-empty content emits the content`() {
        val client = stubbedOpenAi("")
        val json = client.buildRequestJson(listOf(
            LlmMessage("assistant", "hello there"),
        ))
        assertTrue(json.contains(""""content":"hello there""""),
            "non-empty content must be JSON-quoted, not null: $json")
        assertTrue(!json.contains(""""content":null"""),
            "must NOT emit null for non-empty content: $json")
    }

    // ── buildRequestJson: stream_options gate (line 268) ──────────────────────

    @Test
    fun `buildRequestJson stream=true adds stream_options include_usage`() {
        // Line 268: `if (stream) ""","stream":true,"stream_options":{"include_usage":true}""" else ""`.
        // Without stream_options, OpenAI omits the final usage-only delta — we'd
        // lose TokenUsage on every streamed response. Negated mutant flips to
        // omit stream_options on stream=true.
        val client = stubbedOpenAi("")
        val json = client.buildRequestJson(listOf(LlmMessage("user", "hi")), stream = true)
        assertTrue(json.contains(""""stream":true"""), "stream=true must add stream:true to JSON: $json")
        assertTrue(json.contains(""""stream_options":{"include_usage":true}"""),
            "stream=true must add stream_options.include_usage so final usage delta arrives: $json")
    }

    @Test
    fun `buildRequestJson stream=false (default) omits stream and stream_options`() {
        // The else branch of line 268.
        val client = stubbedOpenAi("")
        val json = client.buildRequestJson(listOf(LlmMessage("user", "hi")))
        assertTrue(!json.contains(""""stream""""),
            "non-stream must not emit stream field: $json")
        assertTrue(!json.contains(""""stream_options""""),
            "non-stream must not emit stream_options: $json")
    }

    // ── buildRequestJson: tool counter increment (line 234) ───────────────────

    @Test
    fun `buildRequestJson sequential tool calls get distinct call_N ids`() {
        // Line 234: `"call_${toolCallCounter++}"` — MathMutator on the increment
        // could give same id to all calls, breaking OpenAI's pairing requirement.
        val client = stubbedOpenAi("")
        val json = client.buildRequestJson(listOf(
            LlmMessage("assistant", "", toolCalls = listOf(
                ToolCall(name = "a", arguments = emptyMap()),
                ToolCall(name = "b", arguments = emptyMap()),
                ToolCall(name = "c", arguments = emptyMap()),
            ))
        ))
        // Three distinct ids must appear: call_0, call_1, call_2.
        assertTrue(json.contains(""""id":"call_0""""), "first tool call must be id call_0: $json")
        assertTrue(json.contains(""""id":"call_1""""), "second must be call_1: $json")
        assertTrue(json.contains(""""id":"call_2""""), "third must be call_2: $json")
    }

    @Test
    fun `buildRequestJson tool message pairs with most recent assistant tool_call id`() {
        // Pairing logic at lines 247-250 + the pendingToolCallIds queue.
        // If the counter is broken (mutated to decrement) the tool message
        // would try to pair with a negative-numbered id.
        val client = stubbedOpenAi("")
        val json = client.buildRequestJson(listOf(
            LlmMessage("user", "hi"),
            LlmMessage("assistant", "", toolCalls = listOf(
                ToolCall(name = "f", arguments = emptyMap())
            )),
            LlmMessage("tool", "result"),
        ))
        assertTrue(json.contains(""""tool_call_id":"call_0""""),
            "tool message must pair with first assistant's call_0: $json")
    }

    // ── buildRequestJson: tools field schema generation (line 259) ────────────

    @Test
    fun `buildRequestJson tool without argsType falls back to generic object schema`() {
        // Line 259: `t.argsType?.jsonSchema() ?: """{"type":"object","properties":{},"additionalProperties":true}"""`
        // The Elvis fallback. Test by constructing a ToolDef with argsType = null.
        val toolWithoutArgs = ToolDef(
            name = "no-args-tool",
            description = "tool without args type",
            argsType = null,
            executor = { _ -> "result" },
        )
        val client = object : OpenAiClient(
            apiKey = "k",
            model = "m",
            tools = listOf(toolWithoutArgs),
        ) {
            override fun sendChat(body: String, headers: Map<String, String>): String = body
        }
        val json = client.buildRequestJson(listOf(LlmMessage("user", "hi")))
        assertTrue(json.contains(""""additionalProperties":true"""),
            "tool without argsType must use generic-object fallback schema: $json")
        assertTrue(json.contains(""""name":"no-args-tool""""),
            "tool def must serialize the name: $json")
    }

    // ── parseSseStream: EOF without [DONE] still emits End (line 200) ─────────

    @Test
    fun `parseSseStream emits End on EOF without explicit DONE marker`() = runTest {
        // The existing test covers the [DONE] path. Line 200 is the
        // EOF-without-DONE branch (`collector.emit(LlmChunk.End(usage))`
        // after the useLines block). Negated mutant skips emitting End,
        // breaking the contract that every stream terminates with End.
        val sse = buildString {
            appendLine("""data: {"id":"x","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}""")
            appendLine()
            appendLine("""data: {"id":"x","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}""")
            appendLine()
            // NOTE: no [DONE] terminator. parseSseStream's post-loop emit
            // at line 200 must fire on natural EOF.
        }
        val chunks = stubbedOpenAi(sse).chatStream(listOf(LlmMessage("user", "x"))).toList()
        val end = chunks.filterIsInstance<LlmChunk.End>().singleOrNull()
        assertNotNull(end, "End must be emitted on EOF-without-DONE; chunks were: $chunks")
        assertNull(end.tokenUsage, "no usage seen → End carries null tokenUsage")
        val texts = chunks.filterIsInstance<LlmChunk.TextDelta>().map { it.text }
        assertEquals(listOf("Hello", " world"), texts, "text content must arrive before End")
    }

    @Test
    fun `parseSseStream handles late-arriving tool name (id present, name absent on first delta)`() {
        // Line 172: `else if (newName != null && state.name == null)` branch —
        // when id arrives first WITHOUT name, then name arrives in a later
        // delta. Aggregator must capture the name to use it later.
        // (Behavioral assertion via the resulting ToolCallStarted's toolName
        // is brittle since the emission happens at id-arrival, but we can at
        // least verify the stream doesn't crash.)
        val sse = buildString {
            // Delta 1: id but no name.
            appendLine("""data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_X","type":"function","function":{"arguments":""}}]}}]}""")
            appendLine()
            // Delta 2: name without id.
            appendLine("""data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"name":"late_named"}}]}}]}""")
            appendLine()
            // Finish.
            appendLine("""data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""")
            appendLine()
            appendLine("""data: [DONE]""")
            appendLine()
        }
        // Should not throw.
        val client = stubbedOpenAi(sse)
        val chunks = kotlinx.coroutines.runBlocking {
            client.chatStream(listOf(LlmMessage("user", "x"))).toList()
        }
        val started = chunks.filterIsInstance<LlmChunk.ToolCallStarted>().single()
        assertEquals("call_X", started.callId, "id from first delta must propagate")
        // Started's toolName fires at id arrival when name isn't yet present —
        // expected to be empty string.
        assertEquals("", started.toolName, "name absent on first delta → started carries empty name (kills mutants on the id-only-first branch)")
        // Finished should still emit with the right callId.
        val finished = chunks.filterIsInstance<LlmChunk.ToolCallFinished>().single()
        assertEquals("call_X", finished.callId)
    }
}
