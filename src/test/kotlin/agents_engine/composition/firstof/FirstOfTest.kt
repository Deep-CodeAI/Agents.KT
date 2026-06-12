package agents_engine.composition.firstof

import agents_engine.core.agent
import agents_engine.runtime.events.AgentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

// #3869 — speculative execution: first success wins, losers cancelled,
// branch failures don't settle the race, all-fail throws, audit listener
// names winner/cancelled, session streams under the winner's terminal id.

class FirstOfTest {

    // Suspendable "agents": built on real Agent instances with suspending
    // session-aware tools is overkill here — implementedBy is sync, so we
    // model latency with Thread.sleep (each branch runs on its own coroutine).
    private fun delayedAgent(name: String, millis: Long, value: String) = agent<String, String>(name) {
        skills {
            skill<String, String>("answer", "Answers after a delay") {
                implementedBy { Thread.sleep(millis); value }
            }
        }
    }

    private fun failingAgent(name: String) = agent<String, String>(name) {
        skills {
            skill<String, String>("explode", "Always throws") {
                implementedBy { error("$name failed") }
            }
        }
    }

    @Test
    fun `fastest branch wins and losers are cancelled`() {
        var settled: Triple<String, List<String>, Long>? = null
        val race = firstOf(
            delayedAgent("slow", 800, "slow-answer"),
            delayedAgent("fast", 30, "fast-answer"),
        ).onRaceSettled { winner, cancelled, elapsed -> settled = Triple(winner, cancelled, elapsed) }

        val started = System.nanoTime()
        assertEquals("fast-answer", race("q"))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(elapsedMs < 700, "race must settle on the fast branch, not wait for slow ($elapsedMs ms)")
        assertEquals("fast", settled?.first)
        assertEquals(listOf("slow"), settled?.second)
    }

    @Test
    fun `a failing branch does not settle the race`() {
        val race = firstOf(
            failingAgent("broken"),
            delayedAgent("working", 50, "answer"),
        )
        assertEquals("answer", race("q"))
    }

    @Test
    fun `all branches failing throws the last failure instead of hanging`() {
        val race = firstOf(failingAgent("a"), failingAgent("b"))
        val e = assertFailsWith<IllegalStateException> { race("q") }
        assertTrue("failed" in (e.message ?: ""), "failure message travels; got: ${e.message}")
    }

    @Test
    fun `speculative races the same agent n times and settles once`() {
        val ran = AtomicBoolean(false)
        val a = agent<String, String>("sampler") {
            skills {
                skill<String, String>("answer", "Answers") {
                    implementedBy { ran.set(true); "v" }
                }
            }
        }
        assertEquals("v", a.speculative(3)("q"))
        assertTrue(ran.get())
    }

    @Test
    fun `single placement applies — a raced agent cannot be reused elsewhere`() {
        val a = delayedAgent("a", 1, "x")
        val b = delayedAgent("b", 1, "y")
        firstOf(a, b)
        assertFailsWith<IllegalArgumentException> { firstOf(a, b) }
    }

    @Test
    fun `session streams racer events and completes under the winner's id`() = runTest {
        val race = firstOf(
            delayedAgent("turtle", 500, "late"),
            delayedAgent("hare", 10, "early"),
        )
        val session = race.session("q")
        val events = session.events.toList()
        assertEquals("early", session.await())

        val terminal = events.last()
        assertIs<AgentEvent.Completed<String>>(terminal)
        assertEquals("hare", terminal.agentId, "terminal carries the winner's id; got: $events")
        assertTrue(
            events.any { it is AgentEvent.SkillStarted && it.agentId == "hare" },
            "winner's events must stream; got: $events",
        )
    }
}
