package agents_engine.runtime.events

import agents_engine.composition.pipeline.session
import agents_engine.composition.pipeline.then
import agents_engine.core.agent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

// #1745 — Pipeline.session(input) flows events from every inner agent
// with its own agentId. Sequential between nodes (typed boundary forces
// a complete MID value to flow from a to b); streaming WITHIN a node.

class PipelineSessionTest {

    @Test
    fun `pipeline session emits ordered events from both agents plus a terminal Completed`() = runTest {
        val parse = agent<String, Int>("parse") {
            skills {
                skill<String, Int>("length", "Computes input length") {
                    implementedBy { it.length }
                }
            }
        }
        val describe = agent<Int, String>("describe") {
            skills {
                skill<Int, String>("format", "Formats integer") {
                    implementedBy { "n=$it" }
                }
            }
        }
        val pipeline = parse then describe

        val session = pipeline.session("hello")
        val events = session.events.toList()
        val output = session.await()

        assertEquals("n=5", output, "pipeline output threads parse→describe correctly")
        // #4491 — stage markers bracket each component: Stage/Skill pairs per agent + terminal.
        assertEquals(9, events.size, "expected 2×(StageStarted+Skill pair+StageCompleted) + 1 Completed; got: $events")

        val e0 = events[0]; assertIs<AgentEvent.StageStarted>(e0); assertEquals("parse", e0.stageName)
        val e1 = events[1]; assertIs<AgentEvent.SkillStarted>(e1); assertEquals("parse", e1.agentId); assertEquals("length", e1.skillName)
        val e2 = events[2]; assertIs<AgentEvent.SkillCompleted>(e2); assertEquals("parse", e2.agentId); assertEquals("length", e2.skillName)
        val e3 = events[3]; assertIs<AgentEvent.StageCompleted>(e3); assertEquals("parse", e3.stageName)
        val e4Start = events[4]; assertIs<AgentEvent.StageStarted>(e4Start); assertEquals("describe", e4Start.stageName)
        val e5 = events[5]; assertIs<AgentEvent.SkillStarted>(e5); assertEquals("describe", e5.agentId)
        val e6 = events[6]; assertIs<AgentEvent.SkillCompleted>(e6); assertEquals("describe", e6.agentId)
        val e7 = events[7]; assertIs<AgentEvent.StageCompleted>(e7); assertEquals("describe", e7.stageName)
        val e4 = events[8]; assertIs<AgentEvent.Completed<String>>(e4)
        assertEquals("describe", e4.agentId, "Completed.agentId uses the last agent's name (its OUT matches Pipeline's OUT)")
        assertEquals("n=5", e4.output)
    }

    @Test
    fun `three-stage pipeline emits events from all three agents — proves Pipeline then Agent overload flows events through`() = runTest {
        // a then b then c is left-associative: (a then b) then c, which goes
        // through the Pipeline<*, *>.then(Agent<*, *>) overload. Before #1746
        // that overload's sessionExec fell back to the default (no events from
        // a or b) — only c's events appeared. After: all three.
        val a = agent<String, Int>("a") {
            skills { skill<String, Int>("len", "Length") { implementedBy { it.length } } }
        }
        val b = agent<Int, Int>("b") {
            skills { skill<Int, Int>("doubled", "Doubles") { implementedBy { it * 2 } } }
        }
        val c = agent<Int, String>("c") {
            skills { skill<Int, String>("describe", "Describe") { implementedBy { "n=$it" } } }
        }
        val pipeline = a then b then c

        val session = pipeline.session("hello")
        val events = session.events.toList()
        val output = session.await()

        assertEquals("n=10", output, "pipeline output: 5 → 10 → \"n=10\"")
        // #4491 — 3 stages × (StageStarted + Skill pair + StageCompleted) + terminal.
        assertEquals(13, events.size, "expected 3×4 stage-bracketed events + 1 Completed; got: $events")
        assertEquals(
            listOf("a", "b", "c"),
            events.filterIsInstance<AgentEvent.StageStarted>().map { it.stageName },
            "stage markers fire in chain order",
        )

        // Ordered: each agent's pair appears in chain order before the next agent runs.
        val agentIds = events.filterIsInstance<AgentEvent.SkillStarted>().map { it.agentId }
        assertEquals(listOf("a", "b", "c"), agentIds, "SkillStarted events must fire in chain order; got: $agentIds")

        val completed = events.last()
        assertIs<AgentEvent.Completed<String>>(completed)
        assertEquals("c", completed.agentId, "terminal Completed uses the LAST agent's name")
    }

    @Test
    fun `pipeline session terminates with Failed when the second agent throws — only first agent's events precede`() = runTest {
        val boom = IllegalStateException("middle blew up")
        val first = agent<String, Int>("first") {
            skills {
                skill<String, Int>("ok", "Returns length") {
                    implementedBy { it.length }
                }
            }
        }
        val second = agent<Int, String>("second") {
            skills {
                skill<Int, String>("explode", "Throws unconditionally") {
                    implementedBy { throw boom }
                }
            }
        }
        val pipeline = first then second

        val session = pipeline.session("hello")
        val events = session.events.toList()

        // First agent should run cleanly — its events appear.
        val firstStarted = events.filterIsInstance<AgentEvent.SkillStarted>().firstOrNull { it.agentId == "first" }
            ?: error("expected SkillStarted(first) before the failure; got: $events")
        val firstCompleted = events.filterIsInstance<AgentEvent.SkillCompleted>().firstOrNull { it.agentId == "first" }
            ?: error("expected SkillCompleted(first) before the failure; got: $events")
        assertEquals("first", firstStarted.agentId)
        assertEquals("first", firstCompleted.agentId)

        // Second agent's SkillStarted may or may not have fired before
        // the implementedBy threw — but the terminal MUST be Failed,
        // not Completed, and must carry the original exception.
        val terminal = events.last()
        assertIs<AgentEvent.Failed>(terminal, "last event must be Failed; got: $terminal")
        // assertSame on cause: AgentEvent.Failed.cause carries the identity instance per #1737's contract.
        assertEquals(boom.message, terminal.cause.message, "terminal cause must carry the original exception")

        // await() rethrows.
        assertFailsWith<IllegalStateException> { session.await() }
    }
}
