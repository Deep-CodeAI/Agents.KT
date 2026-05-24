package agents_engine.langfuse

import agents_engine.core.AgentRuntimeContext
import agents_engine.core.Decision
import agents_engine.core.agent
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.TokenUsage
import agents_engine.model.ToolCall
import agents_engine.observability.observe
import agents_engine.runtime.events.AgentEvent
import agents_engine.runtime.events.session
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LangfuseBridgeTest {

    @Test
    fun `session and model turn events produce a trace with a child generation`() = runTest {
        val sink = RecordingSink()
        val bridge = bridge(sink)
        val usage = TokenUsage(promptTokens = 11, completionTokens = 5, provider = "ollama", model = "llama-test")
        val stub = ModelClient { LlmResponse.Text("done", usage) }
        val a = agent<String, String>("langfuse-agent") {
            model { ollama("llama-test"); client = stub }
            attachManifestHash("sha256:test")
            skills {
                skill<String, String>("respond", "respond") { tools() }
            }
        }.observe(bridge)

        try {
            val session = a.session("hello")
            session.events.toList()
            assertEquals("done", session.await())
            assertTrue(bridge.flush(), "bridge did not flush")
        } finally {
            bridge.close()
        }

        val trace = sink.eventsOf("trace-create").map { it.body }.first { it["name"] == "langfuse-agent.respond" }
        val generation = sink.event("generation-create").body
        assertEquals("langfuse-agent.respond", trace["name"])
        assertNotNull(trace["sessionId"])
        assertEquals(trace["id"], generation["traceId"])

        val traceInput = trace.mapAt("input")
        assertEquals("langfuse-agent", traceInput["agent_id"])
        assertEquals("respond", traceInput["skill_name"])
        val traceMetadata = trace.mapAt("metadata")
        assertEquals("sha256:test", traceMetadata["manifest_hash"])

        assertEquals("respond.model.1", generation["name"])
        assertEquals("llama-test", generation["model"])
        val modelParameters = generation.mapAt("modelParameters")
        assertEquals("ollama", modelParameters["provider"])
        assertEquals(1, modelParameters["turn_index"])

        val generationUpdate = sink.event("generation-update").body
        val output = generationUpdate.mapAt("output")
        assertEquals("text", output["response_type"])
        val usageWire = generationUpdate.mapAt("usage")
        assertEquals(11, usageWire["promptTokens"])
        assertEquals(5, usageWire["completionTokens"])
        val usageDetails = generationUpdate.mapAt("usageDetails")
        assertEquals(11, usageDetails["input_tokens"])
        assertEquals(5, usageDetails["output_tokens"])
        assertTrue("cached_input_tokens" !in usageDetails)
    }

    @Test
    fun `tool call events produce a span with inputs and outputs`() = runTest {
        val sink = RecordingSink()
        val bridge = bridge(sink)
        val responses = ArrayDeque<LlmResponse>().apply {
            add(
                LlmResponse.ToolCalls(
                    listOf(
                        ToolCall(
                            name = "lookup",
                            arguments = mapOf("id" to "42"),
                            rawArguments = """{"id":"42"}""",
                            callId = "call-42",
                        ),
                    ),
                ),
            )
            add(LlmResponse.Text("found"))
        }
        val stub = ModelClient { responses.removeFirst() }
        val a = agent<String, String>("tool-agent") {
            model { ollama("llama-test"); client = stub }
            tools {
                tool("lookup", "lookup") { args: Map<String, Any?> -> "value-${args["id"]}" }
            }
            skills {
                skill<String, String>("respond", "respond") {
                    @Suppress("DEPRECATION")
                    tools("lookup")
                }
            }
        }.observe(bridge)

        try {
            val session = a.session("go")
            session.events.toList()
            assertEquals("found", session.await())
            assertTrue(bridge.flush(), "bridge did not flush")
        } finally {
            bridge.close()
        }

        val trace = sink.eventsOf("trace-create").map { it.body }.first { it["name"] == "tool-agent.respond" }
        val span = sink.event("span-create").body
        assertEquals(trace["id"], span["traceId"])
        assertEquals("tool.lookup", span["name"])

        val spanUpdate = sink.event("span-update").body
        val inputs = spanUpdate.mapAt("input")
        val args = inputs.mapAt("args")
        val outputs = spanUpdate.mapAt("output")
        assertEquals("42", args["id"])
        assertEquals("value-42", outputs["result"])
        assertEquals(false, outputs["is_error"])
    }

    @Test
    fun `failed session records error on the active trace without duplicate fallback trace`() = runTest {
        val sink = RecordingSink()
        val bridge = bridge(sink)
        val a = agent<String, String>("failing-agent") {
            skills {
                skill<String, String>("explode", "explode") {
                    implementedBy { error("boom") }
                }
            }
        }.observe(bridge)

        try {
            val session = a.session("go")
            session.events.toList()
            assertNotNull(runCatching { session.await() }.exceptionOrNull())
            assertTrue(bridge.flush(), "bridge did not flush")
        } finally {
            bridge.close()
        }

        val traceCreates = sink.eventsOf("trace-create").map { it.body }
        assertEquals(1, traceCreates.map { it["id"] }.distinct().size)
        val failedTrace = traceCreates.single { (it["output"] as? Map<*, *>)?.get("status") == "failed" }
        val output = failedTrace.mapAt("output")
        assertEquals("boom", output["error"])
    }

    @Test
    fun `before-skill denial is attached to the fallback trace and emitted as an event`() = runTest {
        val sink = RecordingSink()
        val bridge = bridge(sink)
        val a = agent<String, String>("guarded-agent") {
            skills {
                skill<String, String>("blocked", "blocked") {
                    implementedBy { "unreachable" }
                }
            }
        }.observe(bridge)
        a.onBeforeSkill { Decision.Deny("blocked") }

        try {
            val session = a.session("go")
            session.events.toList()
            assertNotNull(runCatching { session.await() }.exceptionOrNull())
            assertTrue(bridge.flush(), "bridge did not flush")
        } finally {
            bridge.close()
        }

        val trace = sink.eventsOf("trace-create").map { it.body }.first { it["name"] == "guarded-agent" }
        assertTrue("interceptor:deny" in trace.listAt("tags"))
        assertTrue("interceptor:deny" in trace.mapAt("metadata").listAt("tags"))

        val decision = sink.eventsOf("event-create").map { it.body }.single { it["name"] == "interceptor.decision" }
        val input = decision.mapAt("input")
        assertEquals("BeforeSkill", input["point"])
        assertEquals("deny", input["decision"])
        assertEquals("ERROR", decision["level"])
    }

    @Test
    fun `outage and backpressure paths log and never throw into the caller`() {
        val logs = CopyOnWriteArrayList<String>()
        val sink = BlockingSink()
        val bridge = bridge(
            sink = sink,
            ids = List(40) { "id-$it" },
            maxQueuedOperations = 2,
            batchSize = 1,
            logger = { message, _ -> logs += message },
        )
        val context = AgentRuntimeContext(requestId = "req", sessionId = "session")

        try {
            bridge.onAgentEvent(AgentEvent.SkillStarted("a", "s0", context))
            assertTrue(sink.entered.await(2, TimeUnit.SECONDS), "dispatch did not start")

            repeat(6) { index ->
                bridge.onAgentEvent(AgentEvent.SkillStarted("a", "s${index + 1}", context))
            }

            assertTrue(logs.any { it.contains("dropped oldest queued operation") }, "expected backpressure log")
        } finally {
            sink.release.countDown()
            bridge.flush()
            bridge.close()
        }
    }

    @Test
    fun `http sink encodes ingestion json fixture shape`() {
        val event = LangfuseIngestionEvent(
            id = "event-1",
            type = "trace-create",
            timestamp = Instant.parse("2026-05-23T10:15:30Z"),
            metadata = linkedMapOf("source" to "agents-kt"),
            body = linkedMapOf(
                "id" to "trace-1",
                "name" to "agent.respond",
                "input" to linkedMapOf("agent_id" to "agent"),
            ),
        )
        val body = encodeJson(
            linkedMapOf(
                "batch" to listOf(event.toWireMap()),
                "metadata" to linkedMapOf(
                    "sdkName" to "agents-kt",
                    "sdkIntegration" to "ObservabilityBridge",
                ),
            ),
        )

        assertEquals(
            """{"batch":[{"id":"event-1", "type":"trace-create", "timestamp":"2026-05-23T10:15:30Z", "metadata":{"source":"agents-kt"}, "body":{"id":"trace-1", "name":"agent.respond", "input":{"agent_id":"agent"}}}], "metadata":{"sdkName":"agents-kt", "sdkIntegration":"ObservabilityBridge"}}""",
            body,
        )
    }

    private fun bridge(
        sink: LangfuseIngestionSink,
        ids: List<String> = List(200) { "id-$it" },
        maxQueuedOperations: Int = 128,
        batchSize: Int = 64,
        logger: (String, Throwable?) -> Unit = { _, _ -> },
    ): LangfuseBridge {
        val iterator = ids.iterator()
        return LangfuseBridge(
            sink = sink,
            maxQueuedOperations = maxQueuedOperations,
            batchSize = batchSize,
            logger = logger,
            clock = Clock.fixed(Instant.parse("2026-05-23T10:15:30.123456Z"), ZoneOffset.UTC),
            idGenerator = {
                check(iterator.hasNext()) { "test id generator exhausted" }
                iterator.next()
            },
        )
    }

    private class RecordingSink : LangfuseIngestionSink {
        val events = CopyOnWriteArrayList<LangfuseIngestionEvent>()

        override fun send(batch: List<LangfuseIngestionEvent>) {
            events += batch
        }

        fun eventsOf(type: String): List<LangfuseIngestionEvent> =
            events.filter { it.type == type }

        fun event(type: String): LangfuseIngestionEvent =
            eventsOf(type).singleOrNull()
                ?: error("expected one $type event; got ${eventsOf(type).map { it.body }}")
    }

    private class BlockingSink : LangfuseIngestionSink {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun send(batch: List<LangfuseIngestionEvent>) {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
        }
    }
}

private fun Map<String, Any?>.mapAt(key: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return this[key] as? Map<String, Any?> ?: error("missing map at $key in $this")
}

private fun Map<String, Any?>.listAt(key: String): List<String> {
    @Suppress("UNCHECKED_CAST")
    return this[key] as? List<String> ?: error("missing list at $key in $this")
}
