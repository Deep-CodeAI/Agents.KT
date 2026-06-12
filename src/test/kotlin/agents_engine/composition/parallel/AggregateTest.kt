package agents_engine.composition.parallel

import agents_engine.composition.pipeline.session
import agents_engine.core.agent
import agents_engine.runtime.events.AgentEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #3872 — one-line ensembles over `/`: every strategy with deterministic
// branches, tie-breaking, the weighted/branch-order contract, and the
// audit shape (strategy name visible on the reducer's events).

class AggregateTest {

    private fun constant(name: String, value: String) = agent<String, String>(name) {
        skills { skill<String, String>("answer", "Answers") { implementedBy { value } } }
    }

    private fun scored(name: String, score: Int) = agent<String, Int>(name) {
        skills { skill<String, Int>("score", "Scores") { implementedBy { score } } }
    }

    @Test
    fun `majorityVote picks the most frequent answer`() {
        val ensemble = (constant("a", "yes") / constant("b", "no") / constant("c", "yes"))
            .aggregate { majorityVote() }
        assertEquals("yes", ensemble("q"))
    }

    @Test
    fun `majorityVote tie breaks to the first-encountered candidate`() {
        val ensemble = (constant("a", "alpha") / constant("b", "beta"))
            .aggregate { majorityVote() }
        assertEquals("alpha", ensemble("q"))
    }

    @Test
    fun `selectByMax picks by the selector`() {
        val ensemble = (scored("a", 3) / scored("b", 9) / scored("c", 5))
            .aggregate { selectByMax { it } }
        assertEquals(9, ensemble("q"))
    }

    @Test
    fun `bestOfN scores each output exactly once`() {
        var scorerCalls = 0
        val ensemble = (constant("a", "short") / constant("b", "a much longer answer"))
            .aggregate { bestOfN { scorerCalls++; it.length.toDouble() } }
        assertEquals("a much longer answer", ensemble("q"))
        assertEquals(2, scorerCalls, "each branch output scored exactly once")
    }

    @Test
    fun `weighted majority lets a heavier agent outvote two light ones`() {
        val expert = constant("expert", "approve")
        val intern1 = constant("intern1", "reject")
        val intern2 = constant("intern2", "reject")
        val ensemble = (expert / intern1 / intern2)
            .aggregate { weighted(mapOf(expert to 3.0)) } // interns default to 1.0
        assertEquals("approve", ensemble("q"))
    }

    @Test
    fun `aggregation appears in the streaming session under the strategy-named reducer`() = runTest {
        val ensemble = (constant("a", "yes") / constant("b", "yes"))
            .aggregate { majorityVote() }

        val session = ensemble.session("q")
        val events = session.events.toList()
        assertEquals("yes", session.await())

        val reducerStart = events.filterIsInstance<AgentEvent.SkillStarted>()
            .firstOrNull { it.agentId == "aggregate-majorityVote" }
        assertTrue(reducerStart != null, "reducer must stream under the strategy name; got: $events")
        assertEquals("majorityVote", reducerStart.skillName)
    }
}
