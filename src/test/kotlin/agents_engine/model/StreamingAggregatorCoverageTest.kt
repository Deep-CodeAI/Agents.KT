package agents_engine.model

import agents_engine.runtime.events.AgentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// #2133 — direct unit coverage for chatOrStream. Same-package access lets us
// call the `internal` entry point and feed it a custom ModelClient whose
// chatStream emits a hand-built sequence of LlmChunk values. The non-emitter
// path is exercised by a ModelClient whose chat() returns a known LlmResponse.
class StreamingAggregatorCoverageTest {

    private class FixedChatClient(private val response: LlmResponse) : ModelClient {
        var chatInvocations = 0
        var lastMessages: List<LlmMessage>? = null
        override fun chat(messages: List<LlmMessage>): LlmResponse {
            chatInvocations++
            lastMessages = messages
            return response
        }
        override suspend fun chatStream(messages: List<LlmMessage>): Flow<LlmChunk> =
            error("chatStream must not be called when emitter is null")
    }

    private class ScriptedStreamClient(private val chunks: List<LlmChunk>) : ModelClient {
        override fun chat(messages: List<LlmMessage>): LlmResponse =
            error("chat must not be called when emitter is non-null")
        override suspend fun chatStream(messages: List<LlmMessage>): Flow<LlmChunk> = flow {
            chunks.forEach { emit(it) }
        }
    }

    @Test
    fun `null emitter forwards to chat and returns its result unchanged`() = runTest {
        val expected = LlmResponse.Text("verbatim", TokenUsage(promptTokens = 3, completionTokens = 5))
        val client = FixedChatClient(expected)
        val messages = listOf(LlmMessage("user", "hi"))

        val result = chatOrStream(client, messages, agentId = "a", skillName = "s", emitter = null)

        assertSame(expected, result, "null-emitter must return the chat() response object as-is")
        assertEquals(1, client.chatInvocations)
        assertEquals(messages, client.lastMessages)
    }

    @Test
    fun `null emitter passes ToolCalls response through untouched`() = runTest {
        val expected = LlmResponse.ToolCalls(
            calls = listOf(ToolCall(name = "fetch", arguments = mapOf("k" to 1))),
            tokenUsage = TokenUsage(promptTokens = 7, completionTokens = 2),
        )
        val client = FixedChatClient(expected)

        val result = chatOrStream(client, emptyList(), agentId = "a", skillName = "s", emitter = null)

        assertSame(expected, result)
    }

    @Test
    fun `single TextDelta produces Token event and Text response with that content`() = runTest {
        val client = ScriptedStreamClient(
            listOf(LlmChunk.TextDelta("hello"), LlmChunk.End(null)),
        )
        val events = mutableListOf<AgentEvent<*>>()

        val result = chatOrStream(client, emptyList(), "agent-1", "greet") { events += it }

        val text = assertIs<LlmResponse.Text>(result)
        assertEquals("hello", text.content)
        assertNull(text.tokenUsage)
        assertEquals(1, events.size)
        val token = assertIs<AgentEvent.Token>(events[0])
        assertEquals("agent-1", token.agentId)
        assertEquals("greet", token.skillName)
        assertEquals("hello", token.text)
    }

    @Test
    fun `multiple TextDelta concatenated in order with one Token per delta`() = runTest {
        val client = ScriptedStreamClient(
            listOf(
                LlmChunk.TextDelta("foo"),
                LlmChunk.TextDelta(" "),
                LlmChunk.TextDelta("bar"),
                LlmChunk.End(null),
            ),
        )
        val events = mutableListOf<AgentEvent<*>>()

        val result = chatOrStream(client, emptyList(), "a", "s") { events += it }

        val text = assertIs<LlmResponse.Text>(result)
        assertEquals("foo bar", text.content)
        val tokens = events.filterIsInstance<AgentEvent.Token>()
        assertEquals(listOf("foo", " ", "bar"), tokens.map { it.text })
    }

    @Test
    fun `End with tokenUsage propagates into LlmResponse Text`() = runTest {
        val usage = TokenUsage(promptTokens = 11, completionTokens = 4)
        val client = ScriptedStreamClient(
            listOf(LlmChunk.TextDelta("x"), LlmChunk.End(usage)),
        )

        val result = chatOrStream(client, emptyList(), "a", "s") { }

        val text = assertIs<LlmResponse.Text>(result)
        assertEquals(usage, text.tokenUsage)
    }

    @Test
    fun `empty stream with only End yields empty Text`() = runTest {
        val client = ScriptedStreamClient(listOf(LlmChunk.End(null)))
        val events = mutableListOf<AgentEvent<*>>()

        val result = chatOrStream(client, emptyList(), "a", "s") { events += it }

        val text = assertIs<LlmResponse.Text>(result)
        assertEquals("", text.content)
        assertNull(text.tokenUsage)
        assertTrue(events.isEmpty(), "no events expected for End-only stream; got: $events")
    }

    @Test
    fun `single tool call lifecycle yields Started + ArgsDelta events and ToolCalls response`() = runTest {
        val client = ScriptedStreamClient(
            listOf(
                LlmChunk.ToolCallStarted("call-1", "lookup"),
                LlmChunk.ToolCallArgumentsDelta("call-1", """{"id":42}"""),
                LlmChunk.ToolCallFinished("call-1", mapOf("id" to 42)),
                LlmChunk.End(TokenUsage(promptTokens = 9, completionTokens = 3)),
            ),
        )
        val events = mutableListOf<AgentEvent<*>>()

        val result = chatOrStream(client, emptyList(), "agent-2", "search") { events += it }

        val tc = assertIs<LlmResponse.ToolCalls>(result)
        assertEquals(1, tc.calls.size)
        val call = tc.calls.single()
        assertEquals("lookup", call.name)
        assertEquals(mapOf("id" to 42), call.arguments)
        assertEquals("call-1", call.callId)
        assertEquals(TokenUsage(promptTokens = 9, completionTokens = 3), tc.tokenUsage)

        assertEquals(2, events.size, "expected [Started, ArgsDelta]; got: $events")
        val started = assertIs<AgentEvent.ToolCallStarted>(events[0])
        assertEquals("agent-2", started.agentId)
        assertEquals("search", started.skillName)
        assertEquals("call-1", started.callId)
        assertEquals("lookup", started.toolName)
        val delta = assertIs<AgentEvent.ToolCallArgumentsDelta>(events[1])
        assertEquals("agent-2", delta.agentId)
        assertEquals("call-1", delta.callId)
        assertEquals("""{"id":42}""", delta.deltaJson)
    }

    @Test
    fun `tool call without ArgumentsDelta still yields ToolCalls with empty arguments map`() = runTest {
        // Started but no ArgumentsDelta and no Finished — pendingArgs has no entry,
        // so the ?: emptyMap() fallback at L 104 fires.
        val client = ScriptedStreamClient(
            listOf(
                LlmChunk.ToolCallStarted("c1", "noargs"),
                LlmChunk.End(null),
            ),
        )

        val result = chatOrStream(client, emptyList(), "a", "s") { }

        val tc = assertIs<LlmResponse.ToolCalls>(result)
        val call = tc.calls.single()
        assertEquals("noargs", call.name)
        assertEquals(emptyMap(), call.arguments)
        assertEquals("c1", call.callId)
    }

    @Test
    fun `multiple ArgumentsDelta forwarded as events and Finished arguments win in final response`() = runTest {
        val client = ScriptedStreamClient(
            listOf(
                LlmChunk.ToolCallStarted("c1", "stream_args"),
                LlmChunk.ToolCallArgumentsDelta("c1", """{"a":"""),
                LlmChunk.ToolCallArgumentsDelta("c1", """1}"""),
                LlmChunk.ToolCallFinished("c1", mapOf("a" to 1)),
                LlmChunk.End(null),
            ),
        )
        val events = mutableListOf<AgentEvent<*>>()

        val result = chatOrStream(client, emptyList(), "a", "s") { events += it }

        val deltas = events.filterIsInstance<AgentEvent.ToolCallArgumentsDelta>()
        assertEquals(2, deltas.size)
        assertEquals(listOf("""{"a":""", """1}"""), deltas.map { it.deltaJson })

        val tc = assertIs<LlmResponse.ToolCalls>(result)
        assertEquals(mapOf("a" to 1), tc.calls.single().arguments)
    }

    @Test
    fun `multiple tool calls preserve arrival order in the response`() = runTest {
        val client = ScriptedStreamClient(
            listOf(
                LlmChunk.ToolCallStarted("first", "alpha"),
                LlmChunk.ToolCallFinished("first", mapOf("x" to 1)),
                LlmChunk.ToolCallStarted("second", "beta"),
                LlmChunk.ToolCallFinished("second", mapOf("y" to 2)),
                LlmChunk.End(null),
            ),
        )

        val result = chatOrStream(client, emptyList(), "a", "s") { }

        val tc = assertIs<LlmResponse.ToolCalls>(result)
        assertEquals(listOf("alpha", "beta"), tc.calls.map { it.name })
        assertEquals(listOf("first", "second"), tc.calls.map { it.callId })
        assertEquals(listOf(mapOf("x" to 1), mapOf("y" to 2)), tc.calls.map { it.arguments })
    }

    @Test
    fun `interleaved tool calls — arrival order preserved and args routed by callId`() = runTest {
        // started1, started2, args2, args1, finished1, finished2 — Anthropic-style interleaving
        val client = ScriptedStreamClient(
            listOf(
                LlmChunk.ToolCallStarted("c1", "alpha"),
                LlmChunk.ToolCallStarted("c2", "beta"),
                LlmChunk.ToolCallArgumentsDelta("c2", """{"k":"v2"}"""),
                LlmChunk.ToolCallArgumentsDelta("c1", """{"k":"v1"}"""),
                LlmChunk.ToolCallFinished("c1", mapOf("k" to "v1")),
                LlmChunk.ToolCallFinished("c2", mapOf("k" to "v2")),
                LlmChunk.End(null),
            ),
        )

        val result = chatOrStream(client, emptyList(), "a", "s") { }

        val tc = assertIs<LlmResponse.ToolCalls>(result)
        assertEquals(listOf("c1", "c2"), tc.calls.map { it.callId }, "arrival order from callOrder")
        assertEquals(listOf("alpha", "beta"), tc.calls.map { it.name })
        assertEquals(mapOf("k" to "v1"), tc.calls[0].arguments)
        assertEquals(mapOf("k" to "v2"), tc.calls[1].arguments)
    }

    @Test
    fun `text accumulated before ToolCallStarted is discarded but Token events still fire`() = runTest {
        // callOrder.isNotEmpty() wins at L 100 — textBuilder is unused. The emitter
        // is still fired unconditionally on each TextDelta inside the collect block.
        val client = ScriptedStreamClient(
            listOf(
                LlmChunk.TextDelta("preamble"),
                LlmChunk.ToolCallStarted("c1", "act"),
                LlmChunk.ToolCallFinished("c1", emptyMap()),
                LlmChunk.End(null),
            ),
        )
        val events = mutableListOf<AgentEvent<*>>()

        val result = chatOrStream(client, emptyList(), "a", "s") { events += it }

        assertIs<LlmResponse.ToolCalls>(result)
        assertEquals(1, events.filterIsInstance<AgentEvent.Token>().size)
    }

    @Test
    fun `ArgumentsDelta for unknown callId still fires the event — emitter is unconditional`() = runTest {
        // The when-branch at L 86-88 forwards the AgentEvent without consulting
        // pendingNames/pendingArgs. Paired with a real Started so we end up in the
        // ToolCalls branch.
        val client = ScriptedStreamClient(
            listOf(
                LlmChunk.ToolCallStarted("c1", "real"),
                LlmChunk.ToolCallArgumentsDelta("orphan", "{}"),
                LlmChunk.ToolCallFinished("c1", emptyMap()),
                LlmChunk.End(null),
            ),
        )
        val events = mutableListOf<AgentEvent<*>>()

        chatOrStream(client, emptyList(), "a", "s") { events += it }

        val delta = events.filterIsInstance<AgentEvent.ToolCallArgumentsDelta>().single()
        assertEquals("orphan", delta.callId)
        assertEquals("{}", delta.deltaJson)
    }
}
