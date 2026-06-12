package agents_engine.composition.loop

import agents_engine.core.agent
import agents_engine.model.LlmResponse
import agents_engine.testing.DeterministicModelClient
import agents_engine.testing.JudgeRubric
import agents_engine.testing.evalGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #3870 — loop { until { } } ergonomics + the evalGate judge adapter.

class LoopUntilTest {

    private fun doubler() = agent<Int, Int>("doubler") {
        skills { skill<Int, Int>("double", "Doubles") { implementedBy { it * 2 } } }
    }

    @Test
    fun `until-loop re-feeds output until the predicate is true`() {
        val grow = doubler().loopUntil(maxIterations = 10) { it >= 100 }
        assertEquals(128, grow(1), "1→2→…→128, first value >= 100")
    }

    @Test
    fun `feedback transforms output into the next input`() {
        val agent = agent<Int, String>("formatter") {
            skills { skill<Int, String>("fmt", "Formats") { implementedBy { "v${it + 1}" } } }
        }
        val loop = agent.loopUntil(
            maxIterations = 5,
            feedback = { it.removePrefix("v").toInt() },
        ) { it == "v3" }
        assertEquals("v3", loop(0), "0→v1→1→v2→2→v3")
    }

    @Test
    fun `maxIterations still bounds a never-true until`() {
        val loop = doubler().loopUntil(maxIterations = 3) { false }
        assertFailsWith<IllegalStateException> { loop(1) }
    }

    @Test
    fun `evalGate passes at threshold and exposes the verdict`() {
        val judgeModel = DeterministicModelClient(
            LlmResponse.Text("""{"score": 4, "rationale": "weak"}"""),
            LlmResponse.Text("""{"score": 8, "rationale": "strong"}"""),
        )
        val gate = evalGate(JudgeRubric("quality", 0..10, judgeModel), threshold = 7)

        assertTrue(!gate.pass("draft-1"), "score 4 must fail the gate")
        assertTrue(gate.pass("draft-2"), "score 8 must pass the gate")
        assertEquals("strong", gate.lastVerdict?.rationale)
    }

    @Test
    fun `evalGate threshold outside the rubric range fails loud`() {
        val rubric = JudgeRubric("quality", 0..10, DeterministicModelClient())
        assertFailsWith<IllegalArgumentException> { evalGate(rubric, threshold = 11) }
    }

    @Test
    fun `reflexion shape — loop until the gate passes`() {
        val judgeModel = DeterministicModelClient(
            LlmResponse.Text("""{"score": 3, "rationale": "needs work"}"""),
            LlmResponse.Text("""{"score": 5, "rationale": "closer"}"""),
            LlmResponse.Text("""{"score": 9, "rationale": "good"}"""),
        )
        val gate = evalGate(JudgeRubric("quality", 0..10, judgeModel), threshold = 7)
        val refiner = agent<String, String>("drafter") {
            skills { skill<String, String>("draft", "Drafts") { implementedBy { "$it+" } } }
        }.loopUntil(maxIterations = 5) { draft -> gate.pass(draft) }

        assertEquals("seed+++", refiner("seed"), "three drafts until the judge passes")
    }
}
