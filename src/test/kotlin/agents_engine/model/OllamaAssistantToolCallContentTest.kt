package agents_engine.model

import agents_engine.generation.LenientJsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for #1694 — Ollama Cloud strict-validator bug.
 *
 * When an `assistant` message carries `tool_calls` and no textual content,
 * the wire shape must be `"content": null` (or the key omitted), NOT
 * `"content": ""`. The OpenAI / Ollama chat-completions spec says `content`
 * is required *unless* `tool_calls` is present, and Ollama Cloud's
 * `gpt-oss:*` family (gpt-oss:120b-cloud, gpt-oss:20b-cloud) enforces this
 * strictly — rejecting the empty-string form with 500 Internal Server
 * Error. Local Ollama tolerates either form.
 *
 * Other adapters already do the right thing:
 * - `ClaudeClient`  — empty content → no `{type:"text"}` block emitted,
 *   only `{type:"tool_use"}` blocks go on the wire.
 * - `OpenAiClient`  — already null-coerces empty content.
 *
 * This test pins OllamaClient to the same contract.
 */
class OllamaAssistantToolCallContentTest {

    private fun client(tools: List<ToolDef> = emptyList()): OllamaClient =
        OllamaClient(model = "gpt-oss:120b-cloud", temperature = 0.0, tools = tools)

    private fun parseMessages(body: String): List<Map<*, *>> {
        val root = LenientJsonParser.parse(body) as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        return (root["messages"] as List<*>).map { it as Map<*, *> }
    }

    @Test
    fun `assistant with tool_calls and blank content emits content as JSON null, not empty string`() {
        val body = client().buildRequestJson(listOf(
            LlmMessage("user", "what is fib(10)?"),
            LlmMessage("assistant", "", toolCalls = listOf(
                ToolCall(name = "fibonacci", arguments = mapOf("n" to 10)),
            )),
            LlmMessage("tool", "55"),
        ))

        // Wire-level check first — this is the bit Ollama Cloud parses.
        assertTrue(
            body.contains("""{"role":"assistant","content":null,"tool_calls":"""),
            "expected assistant tool-call message with content:null, body was:\n$body",
        )
        assertTrue(
            !body.contains("""{"role":"assistant","content":"","tool_calls":"""),
            "regression — assistant tool-call message still emits empty-string content:\n$body",
        )

        // Decoded check — confirms JSON-level shape, not just substring match.
        val assistant = parseMessages(body)[1]
        assertEquals("assistant", assistant["role"])
        assertNull(assistant["content"], "content must decode to JSON null on assistant tool-call turns")
        assertTrue(assistant["tool_calls"] is List<*>, "tool_calls must survive intact")
    }

    @Test
    fun `assistant with tool_calls AND non-blank content keeps the content string`() {
        val body = client().buildRequestJson(listOf(
            LlmMessage("user", "what is fib(10)?"),
            LlmMessage("assistant", "Let me compute that.", toolCalls = listOf(
                ToolCall(name = "fibonacci", arguments = mapOf("n" to 10)),
            )),
        ))

        val assistant = parseMessages(body)[1]
        assertEquals("Let me compute that.", assistant["content"])
        assertTrue(assistant["tool_calls"] is List<*>)
    }

    @Test
    fun `assistant with content alone (no tool_calls) is unchanged`() {
        val body = client().buildRequestJson(listOf(
            LlmMessage("assistant", "Hello there."),
        ))
        val assistant = parseMessages(body).single()
        assertEquals("Hello there.", assistant["content"])
        assertTrue(assistant["tool_calls"] == null, "no tool_calls expected")
    }

    @Test
    fun `assistant with blank content and no tool_calls keeps content as empty string`() {
        // A legitimate empty-string assistant turn (e.g., model echoed nothing
        // but produced no tool_calls either) must NOT get null-coerced — that
        // would change semantics for non-tool turns.
        val body = client().buildRequestJson(listOf(LlmMessage("assistant", "")))
        val assistant = parseMessages(body).single()
        assertEquals("", assistant["content"])
    }

    @Test
    fun `user system and tool roles always emit content as string, never null`() {
        val body = client().buildRequestJson(listOf(
            LlmMessage("system", ""),
            LlmMessage("user", ""),
            LlmMessage("tool", ""),
        ))
        val msgs = parseMessages(body)
        msgs.forEach { m ->
            assertEquals("", m["content"], "non-assistant role had non-string content: $m")
        }
    }

    @Test
    fun `regression — reporter's two-tool-call sequence produces two content_null assistants`() {
        // Mirrors the failing-body.json shape from the bug report:
        // system + user + assistant(tool_call) + tool + assistant(tool_call) + tool.
        val body = client().buildRequestJson(listOf(
            LlmMessage("system", "You are PlanMaster."),
            LlmMessage("user", "Build a glossary plan."),
            LlmMessage("assistant", "", toolCalls = listOf(
                ToolCall(name = "lookup_term", arguments = mapOf("q" to "Fibonacci")),
            )),
            LlmMessage("tool", "{\"def\":\"...\"}"),
            LlmMessage("assistant", "", toolCalls = listOf(
                ToolCall(name = "add_entry", arguments = mapOf("term" to "Fibonacci")),
            )),
            LlmMessage("tool", "{\"ok\":true}"),
        ))

        val msgs = parseMessages(body)
        // Indices 2 and 4 are the two assistant tool-call turns; both must be null.
        assertNull(msgs[2]["content"])
        assertNull(msgs[4]["content"])
        // Indices 3 and 5 are tool results — content stays as the string.
        assertEquals("{\"def\":\"...\"}", msgs[3]["content"])
        assertEquals("{\"ok\":true}", msgs[5]["content"])
    }
}
