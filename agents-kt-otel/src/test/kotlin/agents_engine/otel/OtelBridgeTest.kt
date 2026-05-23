package agents_engine.otel

import agents_engine.core.AgentRuntimeContext
import agents_engine.core.Decision
import agents_engine.core.agent
import agents_engine.model.BudgetReason
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.TokenUsage
import agents_engine.model.ToolCall
import agents_engine.observability.observe
import agents_engine.runtime.events.AgentEvent
import agents_engine.runtime.events.session
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OtelBridgeTest {

    @Test
    fun `session skill events produce an agent invoke span with usage and runtime context`() = runTest {
        val exporter = RecordingSpanExporter()
        val provider = tracerProvider(exporter)
        val tracer = provider.get("agents-kt-test")
        val usage = TokenUsage(promptTokens = 7, completionTokens = 3, provider = "ollama", model = "llama-test")
        val stub = ModelClient { LlmResponse.Text("done", usage) }
        val a = agent<String, String>("otel-agent") {
            model { ollama("llama-test"); client = stub }
            attachManifestHash("sha256:test")
            skills {
                skill<String, String>("respond", "respond") { tools() }
            }
        }.observe(OtelBridge(tracer))

        val session = a.session("hello")
        session.events.toList()
        assertEquals("done", session.await())

        val span = exporter.spanNamed("agent.invoke")
        val attrs = span.attributes.asMap().mapKeys { it.key.key }
        assertEquals("otel-agent", attrs["agent.name"])
                assertEquals("respond", attrs["agent.skill.name"])
        assertEquals("agent", attrs["gen_ai.operation.name"])
        assertTrue((attrs["agent.request.id"] as String).isNotBlank())
        assertTrue((attrs["agent.session.id"] as String).isNotBlank())
        assertEquals("sha256:test", attrs["agent.manifest.hash"])
        assertEquals(7L, attrs["gen_ai.usage.input_tokens"])
        assertEquals(3L, attrs["gen_ai.usage.output_tokens"])
        assertEquals("ollama", attrs["gen_ai.system"])
        assertEquals("llama-test", attrs["gen_ai.request.model"])

        val turnSpan = exporter.spanNamed("gen_ai.chat")
        val turnAttrs = turnSpan.attributes.asMap().mapKeys { it.key.key }
        assertEquals(span.spanId, turnSpan.parentSpanId)
        assertEquals("chat", turnAttrs["gen_ai.operation.name"])
        assertEquals("text", turnAttrs["gen_ai.response.type"])
        assertEquals(7L, turnAttrs["gen_ai.usage.input_tokens"])
    }

    @Test
    fun `tool call events produce child tool span`() = runTest {
        val exporter = RecordingSpanExporter()
        val tracer = tracerProvider(exporter).get("agents-kt-test")
        val responses = ArrayDeque<LlmResponse>().apply {
            add(
                LlmResponse.ToolCalls(
                    listOf(
                        ToolCall(
                            name = "lookup",
                            arguments = mapOf("id" to "42"),
                            rawArguments = """{"id":"42"}""",
                            callId = "call-42",
                        )
                    )
                )
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
        }.observe(OtelBridge(tracer))

        val session = a.session("go")
        session.events.toList()
        session.await()

        val agentSpan = exporter.spanNamed("agent.invoke")
        val toolSpan = exporter.spanNamed("gen_ai.tool")
        val attrs = toolSpan.attributes.asMap().mapKeys { it.key.key }
        assertEquals(agentSpan.spanId, toolSpan.parentSpanId)
        assertEquals("tool", attrs["gen_ai.operation.name"])
                assertEquals("lookup", attrs["tool.name"])
        assertEquals("call-42", attrs["tool.call.id"])
        assertEquals("java.lang.String", attrs["tool.result.type"])
        assertEquals(2, exporter.finished.count { it.name == "gen_ai.chat" })
    }

    @Test
    fun `failed session marks the active agent span as error`() = runTest {
        val exporter = RecordingSpanExporter()
        val tracer = tracerProvider(exporter).get("agents-kt-test")
        val a = agent<String, String>("failing-agent") {
            skills {
                skill<String, String>("explode", "explode") {
                    implementedBy { error("boom") }
                }
            }
        }.observe(OtelBridge(tracer))

        val session = a.session("go")
        session.events.toList()
        val thrown = runCatching { session.await() }.exceptionOrNull()

        assertNotNull(thrown)
        val span = exporter.spanNamed("agent.invoke")
        assertEquals(StatusCode.ERROR, span.status.statusCode)
        assertTrue(span.events.any { it.name == "exception" }, "expected exception event on $span")
    }

    @Test
    fun `started span uses current OTel context as parent`() {
        val exporter = RecordingSpanExporter()
        val provider = tracerProvider(exporter)
        val tracer = provider.get("agents-kt-test")
        val bridge = OtelBridge(tracer)
        val parent = tracer.spanBuilder("outer").startSpan()
        parent.makeCurrent().use {
            bridge.onAgentEvent(
                AgentEvent.SkillStarted(
                    agentId = "child",
                    skillName = "respond",
                    runtimeContext = AgentRuntimeContext(requestId = "req", sessionId = "session"),
                )
            )
            bridge.onAgentEvent(
                AgentEvent.SkillCompleted(
                    agentId = "child",
                    skillName = "respond",
                    tokensUsed = null,
                    runtimeContext = AgentRuntimeContext(requestId = "req", sessionId = "session"),
                )
            )
        }
        parent.end()

        val child = exporter.spanNamed("agent.invoke")
        assertEquals(parent.spanContext.spanId, child.parentSpanId)
        assertNotEquals(Span.getInvalid().spanContext.spanId, child.parentSpanId)
    }

    @Test
    fun `interceptor denial is recorded as an event on the active span`() {
        val exporter = RecordingSpanExporter()
        val tracer = tracerProvider(exporter).get("agents-kt-test")
        val bridge = OtelBridge(tracer)
        val context = AgentRuntimeContext(requestId = "req", sessionId = "session")

        bridge.onAgentEvent(AgentEvent.SkillStarted("guarded", "respond", context))
        bridge.onInterceptorDecision(agents_engine.observability.InterceptorPoint.BeforeToolCall, Decision.Deny("blocked"))
        bridge.onAgentEvent(AgentEvent.SkillCompleted("guarded", "respond", null, context))

        val span = exporter.spanNamed("agent.invoke")
        val event = span.events.single { it.name == "interceptor.deny" }
        val attrs = event.attributes.asMap().mapKeys { it.key.key }
        assertEquals("BeforeToolCall", attrs["interceptor.point"])
            }

    @Test
    fun `budget threshold crossing records an event on the active agent span`() = runTest {
        val exporter = RecordingSpanExporter()
        val tracer = tracerProvider(exporter).get("agents-kt-test")
        val usage = TokenUsage(promptTokens = 80, completionTokens = 0, provider = "ollama", model = "llama-test")
        val stub = ModelClient { LlmResponse.Text("done", usage) }
        val a = agent<String, String>("budget-agent") {
            model { ollama("llama-test"); client = stub }
            budget { maxTokens = 100 }
            skills {
                skill<String, String>("respond", "respond") { tools() }
            }
        }.observe(OtelBridge(tracer))

        val session = a.session("go")
        session.events.toList()
        session.await()

        val span = exporter.spanNamed("agent.invoke")
        val event = span.events.single { it.name == "agent.budget.threshold" }
        val attrs = event.attributes.asMap().mapKeys { it.key.key }
        assertEquals(BudgetReason.TOKENS.name, attrs["budget.reason"])
        assertEquals(0.8, attrs["budget.used_percent"])
    }

    @Test
    fun `before skill denial is recorded on the failure span`() = runTest {
        val exporter = RecordingSpanExporter()
        val tracer = tracerProvider(exporter).get("agents-kt-test")
        val a = agent<String, String>("guarded-agent") {
            skills {
                skill<String, String>("blocked", "blocked") {
                    implementedBy { "unreachable" }
                }
            }
        }.observe(OtelBridge(tracer))
        a.onBeforeSkill { Decision.Deny("blocked") }

        val session = a.session("go")
        session.events.toList()
        assertNotNull(runCatching { session.await() }.exceptionOrNull())

        val span = exporter.spanNamed("agent.invoke")
        assertEquals(StatusCode.ERROR, span.status.statusCode)
        val event = span.events.single { it.name == "interceptor.deny" }
        val attrs = event.attributes.asMap().mapKeys { it.key.key }
        assertEquals("BeforeSkill", attrs["interceptor.point"])
            }

    private fun tracerProvider(exporter: SpanExporter): SdkTracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()

    private class RecordingSpanExporter : SpanExporter {
        val finished = CopyOnWriteArrayList<SpanData>()

        fun spanNamed(name: String): SpanData =
            finished.singleOrNull { it.name == name }
                ?: error("missing span '$name'; got ${finished.map { it.name }}")

        override fun export(spans: Collection<SpanData>): CompletableResultCode {
            finished += spans
            return CompletableResultCode.ofSuccess()
        }

        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }
}
