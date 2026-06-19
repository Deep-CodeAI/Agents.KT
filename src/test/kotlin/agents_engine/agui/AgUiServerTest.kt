package agents_engine.agui

import agents_engine.core.agent
import agents_engine.core.skill
import agents_engine.generation.LenientJsonParser
import agents_engine.runtime.events.AgentEvent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #4523 (PRD §12.7) — AG-UI serving. Two layers: the pure AgUiEventBridge (AgentEvent -> AG-UI events,
// hermetic, exercises text + tool + step + error families) and AgUiServer (RunAgentInput POST -> SSE,
// in-process round trip with a real agent). Mirrors McpServer/A2AServer/NlWebServer from(agent).
class AgUiServerTest {

    private val http = HttpClient.newHttpClient()

    @Suppress("UNCHECKED_CAST")
    private fun parse(json: String): Map<String, Any?> = LenientJsonParser.parse(json) as Map<String, Any?>

    // ---- AgUiEventBridge: the AgentEvent -> AG-UI mapping ----

    @Test
    fun `bridge wraps streamed text and a tool call in the run envelope with correct ordering`() {
        val b = AgUiEventBridge(threadId = "t1", runId = "r1")
        val out = mutableListOf<String>()
        out += b.runStarted()
        out += b.onEvent(AgentEvent.Token(agentId = "a", skillName = "s", text = "Hel"))
        out += b.onEvent(AgentEvent.Token(agentId = "a", skillName = "s", text = "lo"))
        out += b.onEvent(AgentEvent.ToolCallStarted(agentId = "a", skillName = "s", callId = "c1", toolName = "lookup"))
        out += b.onEvent(AgentEvent.ToolCallArgumentsDelta(agentId = "a", callId = "c1", deltaJson = """{"q":1}"""))
        out += b.onEvent(
            AgentEvent.ToolCallFinished(
                agentId = "a", callId = "c1", toolName = "lookup",
                arguments = emptyMap(), result = "ok", isError = false,
            ),
        )
        out += b.onEvent(AgentEvent.Completed(agentId = "a", output = "done", tokensUsed = null))

        val types = out.map { parse(it)["type"] }
        assertEquals(
            listOf(
                "RUN_STARTED",
                "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", // first token opens the message
                "TEXT_MESSAGE_CONTENT",                       // second token, same message (no new START)
                "TEXT_MESSAGE_END", "TOOL_CALL_START",        // text closes before the tool call
                "TOOL_CALL_ARGS",
                "TOOL_CALL_END",
                "RUN_FINISHED",
            ),
            types,
        )
        // a single message id threads the text events
        val textEvents = out.map { parse(it) }.filter { (it["type"] as String).startsWith("TEXT_MESSAGE") }
        val ids = textEvents.map { it["messageId"] }.toSet()
        assertEquals(1, ids.size)
        assertEquals("lo", parse(out[3])["delta"]) // second content delta
        assertEquals("lookup", parse(out[5])["toolCallName"])
    }

    @Test
    fun `bridge surfaces a non-streamed final output as one message`() {
        val b = AgUiEventBridge("t", "r")
        val out = b.onEvent(AgentEvent.Completed(agentId = "a", output = "the answer", tokensUsed = null))
        assertEquals(
            listOf("TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "RUN_FINISHED"),
            out.map { parse(it)["type"] },
        )
        assertEquals("the answer", parse(out[1])["delta"])
    }

    @Test
    fun `bridge maps reasoning to a REASONING block that closes before the answer text`() {
        val b = AgUiEventBridge(threadId = "t", runId = "r")
        val out = mutableListOf<String>()
        out += b.onEvent(AgentEvent.Reasoning(agentId = "a", skillName = "s", text = "Let me "))
        out += b.onEvent(AgentEvent.Reasoning(agentId = "a", skillName = "s", text = "think."))
        out += b.onEvent(AgentEvent.Token(agentId = "a", skillName = "s", text = "Answer"))
        out += b.onEvent(AgentEvent.Completed(agentId = "a", output = "Answer", tokensUsed = null))

        assertEquals(
            listOf(
                "REASONING_START", "REASONING_MESSAGE_START", "REASONING_MESSAGE_CONTENT", // first chunk opens
                "REASONING_MESSAGE_CONTENT",                                               // second chunk, same block
                "REASONING_MESSAGE_END", "REASONING_END",  // reasoning closes before the first answer token
                "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT",
                "TEXT_MESSAGE_END", "RUN_FINISHED",
            ),
            out.map { parse(it)["type"] },
        )
        // one message id threads the reasoning events, distinct from the text message id
        val reasoning = out.map { parse(it) }.filter { (it["type"] as String).startsWith("REASONING") }
        assertEquals(1, reasoning.mapNotNull { it["messageId"] }.toSet().size)
        assertEquals("think.", parse(out[3])["delta"])
    }

    @Test
    fun `bridge closes an open reasoning block before a tool call`() {
        val b = AgUiEventBridge("t", "r")
        val out = mutableListOf<String>()
        out += b.onEvent(AgentEvent.Reasoning(agentId = "a", skillName = "s", text = "deciding"))
        out += b.onEvent(AgentEvent.ToolCallStarted(agentId = "a", skillName = "s", callId = "c1", toolName = "lookup"))
        // reasoning must be closed (END+END) before TOOL_CALL_START — no half-open thinking block
        assertEquals(
            listOf("REASONING_START", "REASONING_MESSAGE_START", "REASONING_MESSAGE_CONTENT",
                "REASONING_MESSAGE_END", "REASONING_END", "TOOL_CALL_START"),
            out.map { parse(it)["type"] },
        )
    }

    @Test
    fun `bridge maps failure to RUN_ERROR`() {
        val b = AgUiEventBridge("t", "r")
        val out = b.onEvent(AgentEvent.Failed(agentId = "a", cause = IllegalStateException("boom")))
        assertEquals("RUN_ERROR", parse(out.single())["type"])
        assertEquals("boom", parse(out.single())["message"])
    }

    // ---- AgUiServer: RunAgentInput POST -> SSE ----

    private fun echoAgent() = agent<String, String>("echo") {
        skills {
            skill<String, String>("reply", "Echoes the user input") { implementedBy { "echo: $it" } }
        }
    }

    private fun post(url: String, body: String, bearer: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder().uri(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body))
        bearer?.let { b.header("Authorization", "Bearer $it") }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun sseTypes(body: String): List<String> =
        body.split("\n\n").filter { it.startsWith("data: ") }.map { parse(it.removePrefix("data: "))["type"] as String }

    @Test
    fun `server streams a RunAgentInput as AG-UI SSE events`() {
        val server = AgUiServer.from(echoAgent()).start()
        try {
            val body = """{"threadId":"t1","runId":"r1","messages":[{"role":"user","content":"hi"}]}"""
            val resp = post(server.url, body)
            assertEquals(200, resp.statusCode())
            val contentType = resp.headers().firstValue("content-type").orElse("")
            assertTrue(contentType.startsWith("text/event-stream"), "SSE content-type")
            val types = sseTypes(resp.body())
            assertEquals("RUN_STARTED", types.first())
            assertEquals("RUN_FINISHED", types.last())
            assertTrue("STEP_STARTED" in types, types.toString())
            // the deterministic skill's output is surfaced as a text message
            val contents = resp.body().split("\n\n").filter { it.startsWith("data: ") }
                .map { parse(it.removePrefix("data: ")) }
                .filter { it["type"] == "TEXT_MESSAGE_CONTENT" }
            assertTrue(
                contents.any { (it["delta"] as String).contains("echo: hi") },
                "final output must reach the stream",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `server rejects a body with no user message`() {
        val server = AgUiServer.from(echoAgent()).start()
        try {
            val resp = post(server.url, """{"threadId":"t1","messages":[]}""")
            assertEquals(400, resp.statusCode())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `server bearer auth rejects without token and accepts with it`() {
        val server = AgUiServer.from(echoAgent(), bearerToken = "s3cret").start()
        try {
            val body = """{"messages":[{"role":"user","content":"hi"}]}"""
            assertEquals(401, post(server.url, body).statusCode())
            assertEquals(200, post(server.url, body, bearer = "s3cret").statusCode())
        } finally {
            server.stop()
        }
    }
}
