package agents_engine.observability

import agents_engine.composition.pipeline.session
import agents_engine.composition.pipeline.then
import agents_engine.core.AgentRuntimeContext
import agents_engine.core.Decision
import agents_engine.core.PipelineEvent
import agents_engine.core.agent
import agents_engine.core.observe
import agents_engine.model.BudgetReason
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.TokenUsage
import agents_engine.runtime.events.AgentEvent
import agents_engine.runtime.events.session
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ObservabilityBridgeTest {

    @Test
    fun `observe bridge forwards existing pipeline event surface`() {
        val bridge = RecordingBridge()
        val a = agent<String, String>("bridge-agent") {
            skills {
                skill<String, String>("echo", "echo") { implementedBy { it } }
            }
        }.observe(bridge)

        assertEquals("hello", a("hello"))

        val event = bridge.pipelineEvents.single()
        assertIs<PipelineEvent.SkillChosen>(event)
        assertEquals("bridge-agent", event.agentName)
        assertEquals("echo", event.skillName)
        assertEquals(event.requestId, event.runtimeContext.requestId)
    }

    @Test
    fun `observe bridge preserves existing pipeline observers`() {
        val prior = mutableListOf<String>()
        val bridge = RecordingBridge()
        val a = agent<String, String>("stacked-observers") {
            skills {
                skill<String, String>("echo", "echo") { implementedBy { it } }
            }
        }
        a.observe { event ->
            if (event is PipelineEvent.SkillChosen) prior += event.skillName
        }
        a.observe(bridge)

        assertEquals("x", a("x"))

        assertEquals(listOf("echo"), prior)
        assertEquals("echo", (bridge.pipelineEvents.single() as PipelineEvent.SkillChosen).skillName)
    }

    @Test
    fun `observe bridge forwards session AgentEvents`() = runTest {
        val bridge = RecordingBridge()
        val a = agent<String, String>("session-agent") {
            skills {
                skill<String, String>("echo", "echo") { implementedBy { it } }
            }
        }.observe(bridge)

        a.session("hello").events.toList()

        assertEquals(
            listOf("SkillStarted", "SkillCompleted", "Completed"),
            bridge.agentEvents.map { it::class.simpleName },
        )
        assertEquals("session-agent", bridge.agentEvents.first().agentId)
    }

    @Test
    fun `observed agent forwards AgentEvents when run inside a pipeline session`() = runTest {
        val bridge = RecordingBridge()
        val parse = agent<String, Int>("parse-agent") {
            skills {
                skill<String, Int>("length", "length") { implementedBy { it.length } }
            }
        }.observe(bridge)
        val describe = agent<Int, String>("describe-agent") {
            skills {
                skill<Int, String>("format", "format") { implementedBy { "n=$it" } }
            }
        }
        val pipeline = parse then describe

        val session = pipeline.session("hello")
        session.events.toList()
        assertEquals("n=5", session.await())

        assertEquals(
            listOf("SkillStarted", "SkillCompleted"),
            bridge.agentEvents.map { it::class.simpleName },
        )
        assertEquals("parse-agent", bridge.agentEvents.first().agentId)
    }

    @Test
    fun `bridge observes before-interceptor decisions without replacing policy`() {
        val bridge = RecordingBridge()
        val a = agent<String, String>("decision-agent") {
            skills {
                skill<String, String>("blocked", "blocked") { implementedBy { "blocked" } }
                skill<String, String>("safe", "safe") { implementedBy { "safe" } }
            }
            skillSelection { "blocked" }
        }.observe(bridge)

        a.onBeforeSkill { Decision.ProceedWith("safe") }

        assertEquals("safe", a("input"))

        val record = bridge.interceptorDecisions.single()
        assertEquals(InterceptorPoint.BeforeSkill, record.point)
        assertIs<Decision.ProceedWith<String>>(record.decision)
        assertEquals("safe", record.decision.replacement)
    }

    @Test
    fun `observe bridge forwards budget threshold events`() = runTest {
        val bridge = RecordingBridge()
        val usage = TokenUsage(promptTokens = 80, completionTokens = 0, provider = "ollama", model = "llama-test")
        val stub = ModelClient { LlmResponse.Text("done", usage) }
        val a = agent<String, String>("budget-bridge-agent") {
            model { ollama("llama-test"); client = stub }
            budget { maxTokens = 100 }
            skills {
                skill<String, String>("respond", "respond") { tools() }
            }
        }.observe(bridge)

        val session = a.session("hello")
        session.events.toList()
        assertEquals("done", session.await())

        val event = bridge.pipelineEvents.filterIsInstance<PipelineEvent.BudgetThreshold>().single()
        assertEquals("budget-bridge-agent", event.agentName)
        assertEquals(BudgetReason.TOKENS, event.reason)
        assertEquals(0.8, event.usedPercent)
    }

    private class RecordingBridge : ObservabilityBridge {
        val pipelineEvents = mutableListOf<PipelineEvent>()
        val agentEvents = mutableListOf<AgentEvent<*>>()
        val interceptorDecisions = mutableListOf<InterceptorDecisionRecord>()

        override fun onPipelineEvent(event: PipelineEvent) {
            pipelineEvents += event
        }

        override fun onAgentEvent(event: AgentEvent<*>) {
            agentEvents += event
        }

        override fun onInterceptorDecision(point: InterceptorPoint, decision: Decision<*>) {
            interceptorDecisions += InterceptorDecisionRecord(point, decision)
        }
    }

    private data class InterceptorDecisionRecord(
        val point: InterceptorPoint,
        val decision: Decision<*>,
    )

    @Suppress("unused")
    private fun samplePipelineEvent(): PipelineEvent =
        PipelineEvent.SkillChosen(
            agentName = "sample",
            timestamp = Instant.EPOCH,
            skillName = "skill",
            runtimeContext = AgentRuntimeContext(requestId = "req"),
        )
}
