package agents_engine.langsmith

import agents_engine.core.AgentRuntimeContext
import agents_engine.core.Decision
import agents_engine.core.agent
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.TokenUsage
import agents_engine.model.ToolCall
import agents_engine.observability.InterceptorPoint
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

class LangSmithBridgeTest {

    @Test
    fun `session and model turn events produce a chain run with a child llm run`() = runTest {
        val sink = RecordingSink()
        val bridge = bridge(sink)
        val usage = TokenUsage(promptTokens = 11, completionTokens = 5, provider = "ollama", model = "llama-test")
        val stub = ModelClient { LlmResponse.Text("done", usage) }
        val a = agent<String, String>("langsmith-agent") {
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

        val chain = sink.create("chain")
        val llm = sink.create("llm")
        assertEquals("langsmith-agent.respond", chain["name"])
        assertEquals("test-project", chain["session_name"])
        assertEquals(chain["id"], chain["trace_id"])
        assertEquals(chain["id"], llm["trace_id"])
        assertEquals(chain["id"], llm["parent_run_id"])
        assertTrue((llm["dotted_order"] as String).startsWith("${chain["dotted_order"]}."))

        val chainInputs = chain.mapAt("inputs")
        assertEquals("langsmith-agent", chainInputs["agent_id"])
        assertEquals("respond", chainInputs["skill_name"])
        val chainExtra = chain.mapAt("extra").mapAt("metadata")
        assertEquals("sha256:test", chainExtra["manifest_hash"])

        val llmInputs = llm.mapAt("inputs")
        assertEquals("ollama", llmInputs["provider"])
        assertEquals("llama-test", llmInputs["model"])
        assertEquals(1, llmInputs["turn_index"])

        val llmUpdate = sink.updateFor(llm["id"] as String)
        val llmOutputs = llmUpdate.patch.mapAt("outputs")
        val tokenUsage = llmOutputs.mapAt("token_usage")
        assertEquals("text", llmOutputs["response_type"])
        assertEquals(11, tokenUsage["input_tokens"])
        assertEquals(5, tokenUsage["output_tokens"])
    }

    @Test
    fun `tool call events produce child tool run with inputs and outputs`() = runTest {
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

        val chain = sink.create("chain")
        val tool = sink.create("tool")
        assertEquals(chain["id"], tool["parent_run_id"])
        assertEquals("lookup", tool["name"])

        val toolUpdate = sink.updateFor(tool["id"] as String)
        val inputs = toolUpdate.patch.mapAt("inputs")
        val args = inputs.mapAt("args")
        val outputs = toolUpdate.patch.mapAt("outputs")
        assertEquals("42", args["id"])
        assertEquals("value-42", outputs["result"])
        assertEquals(false, outputs["is_error"])
    }

    @Test
    fun `failed session records error on the active run`() = runTest {
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

        val update = sink.updates().last { it.patch["error"] == "boom" }
        assertEquals("boom", update.patch["error"])
        assertNotNull(update.patch["end_time"])
        assertEquals(1, sink.creates().count { it["run_type"] == "chain" })
    }

    @Test
    fun `before-skill denial is attached to the fallback failure run`() = runTest {
        val sink = RecordingSink()
        val bridge = bridge(sink, ids = listOf("deny-run"))
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

        val chain = sink.create("chain")
        assertTrue("interceptor:deny" in chain.listAt("tags"))
        assertTrue("interceptor:deny" in chain.mapAt("extra").listAt("tags"))
        assertEquals("guarded-agent", chain["name"])
        assertNotNull(sink.updateFor("deny-run").patch["error"])
    }

    @Test
    fun `outage and backpressure paths log and never throw into the caller`() {
        val logs = CopyOnWriteArrayList<String>()
        val sink = BlockingSink()
        val bridge = bridge(
            sink = sink,
            ids = List(20) { "run-$it" },
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
    fun `http sink encodes batch json fixture shape`() {
        val body = encodeJson(
            linkedMapOf(
                "post" to listOf(
                    linkedMapOf(
                        "id" to "run-1",
                        "name" to "agent.respond",
                        "run_type" to "chain",
                        "inputs" to linkedMapOf("agent_id" to "agent"),
                    ),
                ),
                "patch" to listOf(
                    linkedMapOf(
                        "id" to "run-1",
                        "outputs" to linkedMapOf("status" to "completed"),
                    ),
                ),
            ),
        )

        assertEquals(
            """{"post":[{"id":"run-1", "name":"agent.respond", "run_type":"chain", "inputs":{"agent_id":"agent"}}], "patch":[{"id":"run-1", "outputs":{"status":"completed"}}]}""",
            body,
        )
    }

    @Test
    fun `redactionFields scrubs named fields from tool args before LangSmith inputs`() = runTest {
        // #2490b — wire agent.policy.redactionFields through the LangSmith
        // bridge so secret-bearing argument fields don't enter the trace.
        val sink = RecordingSink()
        val bridge = bridge(sink, redactionFields = setOf("apiKey", "password"))
        val responses = ArrayDeque<LlmResponse>().apply {
            add(LlmResponse.ToolCalls(listOf(ToolCall(
                name = "lookup",
                arguments = mapOf("id" to "42", "apiKey" to "sk-secret"),
                rawArguments = """{"id":"42","apiKey":"sk-secret"}""",
                callId = "call-r",
            ))))
            add(LlmResponse.Text("ok"))
        }
        val stub = ModelClient { responses.removeFirst() }
        val a = agent<String, String>("redact-agent") {
            model { ollama("llama-test"); client = stub }
            tools { tool("lookup", "lookup") { args: Map<String, Any?> -> "value-${args["id"]}" } }
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
            assertEquals("ok", session.await())
            assertTrue(bridge.flush(), "bridge did not flush")
        } finally {
            bridge.close()
        }

        val tool = sink.create("tool")
        val toolUpdate = sink.updateFor(tool["id"] as String)
        val args = toolUpdate.patch.mapAt("inputs").mapAt("args")
        assertEquals("42", args["id"], "non-redacted fields pass through")
        assertEquals("[REDACTED]", args["apiKey"], "matching field is scrubbed before LangSmith writeout")
    }

    private fun bridge(
        sink: LangSmithRunSink,
        ids: List<String> = List(100) { "run-$it" },
        maxQueuedOperations: Int = 128,
        batchSize: Int = 64,
        logger: (String, Throwable?) -> Unit = { _, _ -> },
        redactionFields: Set<String> = emptySet(),
    ): LangSmithBridge {
        val iterator = ids.iterator()
        return LangSmithBridge(
            project = "test-project",
            sink = sink,
            maxQueuedOperations = maxQueuedOperations,
            batchSize = batchSize,
            logger = logger,
            clock = Clock.fixed(Instant.parse("2026-05-23T10:15:30.123456Z"), ZoneOffset.UTC),
            idGenerator = {
                check(iterator.hasNext()) { "test id generator exhausted" }
                iterator.next()
            },
            redactionFields = redactionFields,
        )
    }

    private class RecordingSink : LangSmithRunSink {
        val operations = CopyOnWriteArrayList<LangSmithRunOperation>()

        override fun send(batch: List<LangSmithRunOperation>) {
            operations += batch
        }

        fun creates(): List<Map<String, Any?>> =
            operations.filterIsInstance<LangSmithRunOperation.Create>().map { it.run }

        fun updates(): List<LangSmithRunOperation.Update> =
            operations.filterIsInstance<LangSmithRunOperation.Update>()

        fun create(runType: String): Map<String, Any?> =
            creates().single { it["run_type"] == runType }

        fun updateFor(runId: String): LangSmithRunOperation.Update =
            updates().lastOrNull { it.runId == runId }
                ?: error("missing update for $runId; got ${updates().map { it.runId }}")
    }

    private class BlockingSink : LangSmithRunSink {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun send(batch: List<LangSmithRunOperation>) {
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
