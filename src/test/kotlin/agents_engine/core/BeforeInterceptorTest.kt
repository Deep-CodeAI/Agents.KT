package agents_engine.core

import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.RepairResult
import agents_engine.model.Tool
import agents_engine.model.ToolCall
import agents_engine.model.ToolDef
import agents_engine.runtime.events.AgentEvent
import agents_engine.runtime.events.session
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BeforeInterceptorTest {

    @Test
    fun `onBeforeSkill can deny a resolved skill before execution and onError observes it`() {
        var executed = false
        var observedError: Throwable? = null
        val a = agent<String, String>("skill-guard") {
            skills {
                skill<String, String>("work", "work") {
                    implementedBy {
                        executed = true
                        "done"
                    }
                }
            }
        }
        a.onBeforeSkill { Decision.Deny("skill disabled") }
        a.onError { observedError = it }

        val ex = assertThrows<RuntimeException> { a("input") }

        assertFalse(executed)
        assertTrue(ex.message!!.contains("skill disabled"))
        assertEquals(ex, observedError)
    }

    @Test
    fun `onBeforeSkill ProceedWith can reroute to another compatible skill`() {
        val a = agent<String, String>("skill-reroute") {
            skills {
                skill<String, String>("blocked", "blocked") { implementedBy { "blocked" } }
                skill<String, String>("safe", "safe") { implementedBy { "safe" } }
            }
            skillSelection { "blocked" }
        }
        a.onBeforeSkill { name ->
            assertEquals("blocked", name)
            Decision.ProceedWith("safe")
        }

        assertEquals("safe", a("input"))
    }

    @Test
    fun `onBeforeTurn ProceedWith replaces messages before the model call`() {
        val client = CapturingClient(LlmResponse.Text("done"))
        val a = agent<String, String>("turn-sanitizer") {
            model { ollama("test"); this.client = client }
            skills { skill<String, String>("s", "s") { tools() } }
        }
        a.onBeforeTurn { messages ->
            Decision.ProceedWith(messages.map {
                if (it.role == "user") it.copy(content = "sanitized") else it
            })
        }

        assertEquals("done", a("ignore me"))

        val user = client.calls.single().single { it.role == "user" }
        assertEquals("sanitized", user.content)
    }

    @Test
    fun `onBeforeTurn Deny aborts before model call and fires onError`() {
        var modelCalls = 0
        var observedError: Throwable? = null
        val client = ModelClient {
            modelCalls++
            LlmResponse.Text("should-not-run")
        }
        val a = agent<String, String>("turn-deny") {
            model { ollama("test"); this.client = client }
            skills { skill<String, String>("s", "s") { tools() } }
        }
        a.onBeforeTurn { Decision.Deny("possible prompt injection") }
        a.onError { observedError = it }

        val ex = assertThrows<RuntimeException> { a("ignore me") }

        assertEquals(0, modelCalls)
        assertTrue(ex.message!!.contains("possible prompt injection"))
        assertEquals(ex, observedError)
    }

    @Test
    fun `onBeforeToolCall Deny feeds a synthetic tool error without executor or onToolError`() {
        val client = CapturingClient(
            LlmResponse.ToolCalls(listOf(
                ToolCall(name = "writeFile", arguments = mapOf("target" to "/etc/passwd")),
            )),
            LlmResponse.Text("blocked handled"),
        )
        var executed = false
        var toolErrorFired = false
        val a = agent<String, String>("tool-deny") {
            lateinit var writeFile: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); this.client = client }
            tools {
                writeFile = tool("writeFile", "write") { _ ->
                    executed = true
                    "wrote"
                }
            }
            onToolError("writeFile") {
                executionError {
                    toolErrorFired = true
                    RepairResult.Fixed("recovered")
                }
            }
            skills { skill<String, String>("s", "s") { tools(writeFile) } }
        }
        a.onBeforeToolCall { _, args ->
            if (args["target"] == "/etc/passwd") Decision.Deny("denied by policy")
            else Decision.Proceed
        }

        assertEquals("blocked handled", a("write"))

        assertFalse(executed)
        assertFalse(toolErrorFired)
        val toolMessage = client.calls[1].single { it.role == "tool" }.content
        assertTrue(toolMessage.contains("denied by policy"), toolMessage)
    }

    @Test
    fun `onBeforeToolCall ProceedWith mutates args seen by executor and onToolUse`() {
        val client = CapturingClient(
            LlmResponse.ToolCalls(listOf(
                ToolCall(name = "echo", arguments = mapOf("text" to "hello")),
            )),
            LlmResponse.Text("done"),
        )
        var executorArgs: Map<String, Any?>? = null
        var observedArgs: Map<String, Any?>? = null
        val a = agent<String, String>("tool-mutate") {
            lateinit var echo: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); this.client = client }
            tools {
                echo = tool("echo", "echo") { args ->
                    executorArgs = args
                    args["traceId"].toString()
                }
            }
            skills { skill<String, String>("s", "s") { tools(echo) } }
            onToolUse { _, args, _ -> observedArgs = args }
        }
        a.onBeforeToolCall { _, args -> Decision.ProceedWith(args + ("traceId" to "t-123")) }

        assertEquals("done", a("echo"))

        assertEquals("t-123", executorArgs!!["traceId"])
        assertEquals("t-123", observedArgs!!["traceId"])
    }

    @Test
    fun `onBeforeToolCall Substitute skips executor but behaves like a tool result`() {
        val client = CapturingClient(
            LlmResponse.ToolCalls(listOf(ToolCall(name = "expensive", arguments = emptyMap()))),
            LlmResponse.Text("done"),
        )
        var executed = false
        var observedResult: Any? = null
        val a = agent<String, String>("tool-substitute") {
            lateinit var expensive: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); this.client = client }
            tools {
                expensive = tool("expensive", "expensive") { _ ->
                    executed = true
                    "real"
                }
            }
            skills { skill<String, String>("s", "s") { tools(expensive) } }
            onToolUse { _, _, result -> observedResult = result }
        }
        a.onBeforeToolCall { _, _ -> Decision.Substitute("cached") }

        assertEquals("done", a("go"))

        assertFalse(executed)
        assertEquals("cached", observedResult)
        assertEquals("cached", client.calls[1].single { it.role == "tool" }.content)
    }

    @Test
    fun `onBeforeToolCall mutates args before session-aware executor`() = runTest {
        val callId = "call-session-mutate"
        val client = CapturingClient(
            LlmResponse.ToolCalls(listOf(
                ToolCall(
                    name = "sessionTool",
                    arguments = mapOf("text" to "hello"),
                    rawArguments = """{"text":"hello"}""",
                    callId = callId,
                ),
            )),
            LlmResponse.Text("done"),
        )
        var sessionArgs: Map<String, Any?>? = null
        val sessionTool = ToolDef(
            name = "sessionTool",
            description = "session-aware",
            executor = { _ -> "fallback" },
            sessionExecutor = { args, _ ->
                sessionArgs = args
                "session-${args["traceId"]}"
            },
        )
        val a = agent<String, String>("session-tool-mutate") {
            model { ollama("test"); this.client = client }
            tools { +sessionTool }
            skills {
                skill<String, String>("s", "s") {
                    @Suppress("DEPRECATION")
                    tools("sessionTool")
                }
            }
        }
        a.onBeforeToolCall { _, args -> Decision.ProceedWith(args + ("traceId" to "s-123")) }

        val session = a.session("go")
        val events = session.events.toList()
        val output = session.await()

        assertEquals("done", output)
        assertEquals("s-123", sessionArgs!!["traceId"])
        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertEquals(callId, finished.callId)
        assertEquals("s-123", finished.arguments["traceId"])
        assertEquals("session-s-123", finished.result)
        assertEquals(false, finished.isError)
    }

    @Test
    fun `onBeforeToolCall runs every interceptor but first non-Proceed decision wins`() {
        val client = CapturingClient(
            LlmResponse.ToolCalls(listOf(ToolCall(name = "danger", arguments = emptyMap()))),
            LlmResponse.Text("done"),
        )
        var executed = false
        val events = mutableListOf<String>()
        val a = agent<String, String>("tool-chain") {
            lateinit var danger: Tool<Map<String, Any?>, Any?>
            model { ollama("test"); this.client = client }
            tools { danger = tool("danger", "danger") { _ -> executed = true; "real" } }
            skills { skill<String, String>("s", "s") { tools(danger) } }
        }
        a.onBeforeToolCall { _, _ ->
            events += "first"
            Decision.Deny("first-deny")
        }
        a.onBeforeToolCall { _, _ ->
            events += "second"
            Decision.Substitute("second-result")
        }

        assertEquals("done", a("go"))

        assertEquals(listOf("first", "second"), events)
        assertFalse(executed)
        val toolMessage = client.calls[1].single { it.role == "tool" }.content
        assertTrue(toolMessage.contains("first-deny"), toolMessage)
        assertFalse(toolMessage.contains("second-result"), toolMessage)
    }

    private class CapturingClient(vararg responses: LlmResponse) : ModelClient {
        private val responses = ArrayDeque(responses.toList())
        val calls = mutableListOf<List<LlmMessage>>()

        override fun chat(messages: List<LlmMessage>): LlmResponse {
            calls += messages.map { message ->
                message.copy(toolCalls = message.toolCalls?.map { it.copy(arguments = it.arguments.toMap()) })
            }
            assertTrue(this.responses.isNotEmpty(), "CapturingClient ran out of responses")
            return this.responses.removeFirst()
        }
    }
}
