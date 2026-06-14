package agents_engine.runtime.events

import agents_engine.composition.forum.times
import agents_engine.composition.loop.loop
import agents_engine.composition.parallel.div
import agents_engine.composition.pipeline.session
import agents_engine.composition.pipeline.then
import agents_engine.core.agent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #4499/#4500 follow-up — streaming over COMPLEX compositions: deep sequential chains with a
 * concurrent fan-out in the middle, a forum stage, and a loop stage. These assert the three
 * properties that matter once many agents share one event stream:
 *  1. **No collision** — every agent's events are attributable to a distinct `agentId`.
 *  2. **No loss** — paced producers fully collected drop nothing (`droppedEvents == 0`).
 *  3. **Exactly one terminal**, attributed to the chain's last agent, after all inner events.
 */
class ComplexStreamingTest {

    private fun echo(name: String) = agent<String, String>(name) {
        skills { skill<String, String>("run", "runs") { implementedBy { "$name:$it" } } }
    }

    private fun reducer(name: String) = agent<List<String>, String>(name) {
        skills { skill<List<String>, String>("join", "joins") { implementedBy { it.sorted().joinToString("|") } } }
    }

    @Test
    fun `head then concurrent fan-out then reducer — no collision, no loss, single terminal`() = runTest {
        // source -> (w1 / w2 / w3) -> join : sequential head, 3-way concurrent middle, sequential tail.
        val pipe = echo("source") then (echo("w1") / echo("w2") / echo("w3")) then reducer("join")
        val session = pipe.session("x")
        val events = session.events.toList()
        val output = session.await()

        // 1. No collision: the three concurrent workers each surface under their own agentId.
        val skillStarts = events.filterIsInstance<AgentEvent.SkillStarted>().map { it.agentId }
        assertTrue(setOf("w1", "w2", "w3").all { it in skillStarts }, "all workers attributable; got: $skillStarts")
        assertEquals(skillStarts.size, skillStarts.toSet().size, "no agentId appears twice; got: $skillStarts")

        // 2. No loss on a fully-collected paced stream.
        assertEquals(0L, session.droppedEvents, "paced producers must not drop")

        // 3. Exactly one terminal Completed, attributed to the reducer, and it is the LAST event.
        val completes = events.filterIsInstance<AgentEvent.Completed<*>>()
        assertEquals(1, completes.size, "exactly one terminal; got: $events")
        val terminal = events.last()
        assertTrue(terminal is AgentEvent.Completed<*> && terminal.agentId == "join", "terminal is the reducer")

        // The concurrent workers' events fall inside the 'parallel' stage's markers.
        val pStart = events.indexOfFirst { it is AgentEvent.StageStarted && it.stageName == "parallel" }
        val pEnd = events.indexOfFirst { it is AgentEvent.StageCompleted && it.stageName == "parallel" }
        val anyWorker = events.indexOfFirst { it is AgentEvent.SkillStarted && it.agentId in setOf("w1", "w2", "w3") }
        assertTrue(pStart in 0 until anyWorker && anyWorker < pEnd, "fan-out streams inside its stage")

        // Data lane: reducer joined all three branch outputs deterministically.
        assertEquals("w1:source:x|w2:source:x|w3:source:x", output)
    }

    @Test
    fun `forum stage inside a pipeline streams participants and captain distinctly`() = runTest {
        val pipe = echo("intake") then (echo("panelist") * echo("captain"))
        val session = pipe.session("topic")
        val events = session.events.toList()
        session.await()

        val ids = events.filterIsInstance<AgentEvent.SkillStarted>().map { it.agentId }.toSet()
        assertTrue(setOf("intake", "panelist", "captain").all { it in ids }, "forum members attributable; got: $ids")
        assertEquals(0L, session.droppedEvents)
        assertEquals(1, events.filterIsInstance<AgentEvent.Completed<*>>().size, "single terminal")
    }

    @Test
    fun `loop stage streams every iteration under one agentId without dropping the terminal`() = runTest {
        // A counter agent that appends a tick each pass; loop runs a fixed number of iterations.
        val ticker = agent<String, String>("ticker") {
            skills { skill<String, String>("tick", "ticks") { implementedBy { "$it." } } }
        }
        // Feed back until three ticks have accumulated, then stop (null).
        val pipe = echo("seed") then ticker.loop(maxIterations = 5) { r ->
            if (r.count { it == '.' } < 3) r else null
        }
        val session = pipe.session("go")
        val events = session.events.toList()
        val output = session.await()

        // The loop body re-runs the same agent; all its events share agentId="ticker" (one component,
        // looped) — that is NOT a collision (single placement), and the terminal still arrives once.
        assertEquals(0L, session.droppedEvents)
        assertEquals(1, events.filterIsInstance<AgentEvent.Completed<*>>().size, "single terminal after the loop")
        assertTrue(output.endsWith("..."), "three loop passes each appended a tick; got: $output")
    }
}
