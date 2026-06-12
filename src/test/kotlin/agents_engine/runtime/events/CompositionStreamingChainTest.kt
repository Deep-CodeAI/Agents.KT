package agents_engine.runtime.events

import agents_engine.composition.branch.branch
import agents_engine.composition.forum.times
import agents_engine.composition.loop.loop
import agents_engine.composition.parallel.div
import agents_engine.composition.pipeline.session
import agents_engine.composition.pipeline.then
import agents_engine.core.agent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// #3866 — streaming flows through EVERY `then` overload: pipelines that chain
// Parallel / Forum / Loop / Branch mid-chain stream those operators' inner
// events through the parent session, not just the terminal Completed.

class CompositionStreamingChainTest {

    private fun lengthAgent(name: String) = agent<String, Int>(name) {
        skills { skill<String, Int>("len", "Length") { implementedBy { it.length } } }
    }

    private fun doubler(name: String) = agent<Int, Int>(name) {
        skills { skill<Int, Int>("double", "Doubles") { implementedBy { it * 2 } } }
    }

    @Test
    fun `agent then parallel streams the leading agent and every branch`() = runTest {
        val head = lengthAgent("head")
        val fanOut = doubler("left") / doubler("right")
        val pipeline = head then fanOut

        val session = pipeline.session("hello")
        val events = session.events.toList()
        val output = session.await()

        assertEquals(listOf(10, 10), output)
        val started = events.filterIsInstance<AgentEvent.SkillStarted>().map { it.agentId }
        assertEquals(setOf("head", "left", "right"), started.toSet(), "all three agents must stream; got: $events")
        // The leading agent completes before any branch starts.
        val headDone = events.indexOfFirst { it is AgentEvent.SkillCompleted && it.agentId == "head" }
        val firstBranch = events.indexOfFirst { it is AgentEvent.SkillStarted && it.agentId != "head" }
        assertTrue(headDone < firstBranch, "head must finish before the fan-out starts; got: $events")
        assertIs<AgentEvent.Completed<List<Int>>>(events.last())
    }

    @Test
    fun `parallel then agent streams branches first and the reducer last`() = runTest {
        val fanOut = lengthAgent("a") / lengthAgent("b")
        val reduce = agent<List<Int>, Int>("reduce") {
            skills { skill<List<Int>, Int>("sum", "Sums") { implementedBy { it.sum() } } }
        }
        val pipeline = fanOut then reduce

        val session = pipeline.session("hello")
        val events = session.events.toList()
        assertEquals(10, session.await())

        val reduceStart = events.indexOfFirst { it is AgentEvent.SkillStarted && it.agentId == "reduce" }
        val lastBranchDone = events.indexOfLast {
            it is AgentEvent.SkillCompleted && it.agentId in setOf("a", "b")
        }
        assertTrue(reduceStart > lastBranchDone, "reducer must start after both branches complete; got: $events")
        assertEquals(
            setOf("a", "b", "reduce"),
            events.filterIsInstance<AgentEvent.SkillStarted>().map { it.agentId }.toSet(),
        )
    }

    @Test
    fun `agent then forum streams the head, all participants, and the captain`() = runTest {
        val head = agent<String, String>("head") {
            skills { skill<String, String>("echo", "Echo") { implementedBy { "topic: $it" } } }
        }
        val forum = agent<String, String>("analyst") {
            skills { skill<String, String>("analyze", "Analyzes") { implementedBy { "analysis" } } }
        } * agent<String, String>("critic") {
            skills { skill<String, String>("critique", "Critiques") { implementedBy { "critique" } } }
        } * agent<String, String>("captain") {
            skills { skill<String, String>("verdict", "Verdict") { implementedBy { "verdict: $it" } } }
        }
        val pipeline = head then forum

        val session = pipeline.session("x")
        val events = session.events.toList()
        session.await()

        assertEquals(
            setOf("head", "analyst", "critic", "captain"),
            events.filterIsInstance<AgentEvent.SkillStarted>().map { it.agentId }.toSet(),
            "head + every forum agent must stream; got: $events",
        )
        val captainStart = events.indexOfFirst { it is AgentEvent.SkillStarted && it.agentId == "captain" }
        val analystDone = events.indexOfFirst { it is AgentEvent.SkillCompleted && it.agentId == "analyst" }
        assertTrue(captainStart > analystDone, "captain streams after participants; got: $events")
    }

    @Test
    fun `agent then loop streams every iteration`() = runTest {
        val head = lengthAgent("head")
        val grow = doubler("grow").loop { out -> if (out < 30) out else null }
        val pipeline = head then grow

        val session = pipeline.session("hello")
        val events = session.events.toList()
        assertEquals(40, session.await(), "5 → 10 → 20 → 40")

        val growStarts = events.count { it is AgentEvent.SkillStarted && it.agentId == "grow" }
        assertEquals(3, growStarts, "three loop iterations must each stream a SkillStarted; got: $events")
    }

    @Test
    fun `agent then branch streams the head, the source, and the routed agent`() = runTest {
        val head = agent<String, String>("head") {
            skills { skill<String, String>("pass", "Pass through") { implementedBy { it } } }
        }
        val source = agent<String, Int>("source") {
            skills { skill<String, Int>("len", "Length") { implementedBy { it.length } } }
        }
        val small = agent<Int, String>("small") {
            skills { skill<Int, String>("fmt", "Format") { implementedBy { "small:$it" } } }
        }
        val routed = source.branch<String, Int, String> {
            on<Int>() then small
        }
        val pipeline = head then routed

        val session = pipeline.session("hello")
        val events = session.events.toList()
        assertEquals("small:5", session.await())

        assertEquals(
            setOf("head", "source", "small"),
            events.filterIsInstance<AgentEvent.SkillStarted>().map { it.agentId }.toSet(),
            "head, branch source, and routed agent must all stream; got: $events",
        )
    }

    @Test
    fun `cancelling a chained session tears down without hanging`() = runTest {
        val head = lengthAgent("head")
        val pipeline = head then (doubler("left") / doubler("right"))

        // .first() cancels the events Flow after one event — structured
        // concurrency must tear the inner sessions down, not hang the test.
        val firstEvent = pipeline.session("hello").events.first()
        assertIs<AgentEvent.SkillStarted>(firstEvent)
        assertEquals("head", firstEvent.agentId)
    }
}
