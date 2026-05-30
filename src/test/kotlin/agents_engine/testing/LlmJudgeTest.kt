package agents_engine.testing

import agents_engine.core.agent
import agents_engine.model.LlmResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2494 — LLM-as-judge scorer. Pins:
 *
 * 1. `judge(label, rubric)` adds an advisory scorer that runs after the
 *    agent succeeds.
 * 2. The verdict surfaces on `EvalResult.judgeVerdicts[label]` as a
 *    `JudgeOutcome.Scored(JudgeVerdict)`.
 * 3. A low judge score does NOT fail the case — judges are advisory;
 *    only deterministic `expect` blocks gate pass/fail.
 * 4. A judge parse error / out-of-range score surfaces as
 *    `JudgeOutcome.Errored` but still doesn't gate pass/fail.
 * 5. Multiple judges per case allowed; each keyed by label.
 * 6. The `judgeSummary` field renders advisory output cleanly with
 *    the `[advisory]` marker.
 * 7. Judges only run if the agent itself succeeded (no judge run on
 *    invocation error).
 */
class LlmJudgeTest {

    private fun judgeReturning(json: String) = DeterministicModelClient(LlmResponse.Text(json))

    private fun simpleAgent(text: String) = agent<String, String>("a") {
        model { ollama("t"); client = DeterministicModelClient(LlmResponse.Text(text)) }
        skills { skill<String, String>("s", "") { tools() } }
    }

    @Test
    fun `judge verdict is captured on EvalResult judgeVerdicts`() {
        val rubric = JudgeRubric(
            criteria = "Tone: warm and helpful.",
            judgeModel = judgeReturning("""{"score":8,"rationale":"clear and warm"}"""),
        )
        val case = eval<String, String>("tone-check") {
            input("hi")
            expect("nonempty") { it.isNotEmpty() }
            judge("tone", rubric)
        }
        val result = case.run(simpleAgent("hello there"))
        assertTrue(result.passed, "deterministic check passed")
        val outcome = result.judgeVerdicts["tone"] as JudgeOutcome.Scored
        assertEquals(8, outcome.verdict.score)
        assertEquals("clear and warm", outcome.verdict.rationale)
    }

    @Test
    fun `low judge score does NOT fail the case (advisory only)`() {
        val rubric = JudgeRubric(
            criteria = "Tone: warm and helpful.",
            judgeModel = judgeReturning("""{"score":2,"rationale":"cold and clipped"}"""),
        )
        val case = eval<String, String>("low-score") {
            input("hi")
            expect("nonempty") { it.isNotEmpty() }
            judge("tone", rubric)
        }
        val result = case.run(simpleAgent("k."))
        // Deterministic expect passed → case passes despite low score
        assertTrue(result.passed, "judge score 2 must not gate case pass/fail")
        assertNull(result.failureMessage)
        val outcome = result.judgeVerdicts["tone"] as JudgeOutcome.Scored
        assertEquals(2, outcome.verdict.score)
    }

    @Test
    fun `judge errors do not gate pass-fail and surface as Errored`() {
        val rubric = JudgeRubric(
            criteria = "Tone.",
            judgeModel = judgeReturning("not valid json"),
        )
        val case = eval<String, String>("bad-judge") {
            input("hi")
            expect("nonempty") { it.isNotEmpty() }
            judge("tone", rubric)
        }
        val result = case.run(simpleAgent("hello"))
        assertTrue(result.passed, "judge parse error does not gate the deterministic pass")
        val outcome = result.judgeVerdicts["tone"]
        assertTrue(outcome is JudgeOutcome.Errored, "non-parseable verdict surfaces as Errored")
    }

    @Test
    fun `out-of-range score surfaces as Errored, not as Scored`() {
        val rubric = JudgeRubric(
            criteria = "Tone.",
            scoreRange = 0..10,
            judgeModel = judgeReturning("""{"score":99,"rationale":"out of range"}"""),
        )
        val case = eval<String, String>("out-of-range") {
            input("hi")
            expect("nonempty") { it.isNotEmpty() }
            judge("tone", rubric)
        }
        val result = case.run(simpleAgent("hello"))
        assertTrue(result.passed)
        val outcome = result.judgeVerdicts["tone"]
        assertTrue(outcome is JudgeOutcome.Errored, "out-of-range score is a judge failure mode")
        assertTrue("99" in (outcome as JudgeOutcome.Errored).errorDetail)
    }

    @Test
    fun `multiple judges per case are keyed by label`() {
        val toneRubric = JudgeRubric(
            criteria = "Tone.",
            judgeModel = judgeReturning("""{"score":7,"rationale":"warm"}"""),
        )
        val relevanceRubric = JudgeRubric(
            criteria = "Relevance to the question.",
            judgeModel = judgeReturning("""{"score":9,"rationale":"on topic"}"""),
        )
        val case = eval<String, String>("multi-judge") {
            input("hi")
            expect("nonempty") { it.isNotEmpty() }
            judge("tone", toneRubric)
            judge("relevance", relevanceRubric)
        }
        val result = case.run(simpleAgent("yes hello"))
        assertEquals(2, result.judgeVerdicts.size)
        assertEquals(7, (result.judgeVerdicts["tone"] as JudgeOutcome.Scored).verdict.score)
        assertEquals(9, (result.judgeVerdicts["relevance"] as JudgeOutcome.Scored).verdict.score)
    }

    @Test
    fun `judgeSummary renders advisory marker on every line`() {
        val rubric = JudgeRubric(
            criteria = "Tone.",
            judgeModel = judgeReturning("""{"score":6,"rationale":"acceptable"}"""),
        )
        val case = eval<String, String>("summary") {
            input("hi")
            expect("nonempty") { it.isNotEmpty() }
            judge("tone", rubric)
        }
        val result = case.run(simpleAgent("hello"))
        val summary = result.judgeSummary
        assertTrue("[advisory]" in summary, "summary marks judges as advisory: $summary")
        assertTrue("tone" in summary)
        assertTrue("6" in summary)
        assertTrue("acceptable" in summary)
    }

    @Test
    fun `judges do not run when the agent invocation itself fails`() {
        val rubric = JudgeRubric(
            criteria = "Tone.",
            judgeModel = judgeReturning("""{"score":10,"rationale":"never reached"}"""),
        )
        val crashingAgent = agent<String, String>("crash") {
            model { ollama("t"); client = DeterministicModelClient() /* empty script → exhaustion */ }
            skills { skill<String, String>("s", "") { tools() } }
        }
        val case = eval<String, String>("crash-then-judge") {
            input("hi")
            expect("never reached") { true }
            judge("tone", rubric)
        }
        val result = case.run(crashingAgent)
        assertFalse(result.passed)
        assertNotNull(result.invocationError)
        assertEquals(emptyMap(), result.judgeVerdicts, "no judges run when the agent didn't return an output")
    }

    @Test
    fun `duplicate judge labels fail fast at builder time`() {
        val rubric = JudgeRubric(
            criteria = "X.",
            judgeModel = judgeReturning("""{"score":5,"rationale":"y"}"""),
        )
        val ex = kotlin.runCatching {
            eval<String, String>("dup") {
                input("hi")
                expect("nonempty") { it.isNotEmpty() }
                judge("tone", rubric)
                judge("tone", rubric)
            }
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("duplicate" in ex.message!!.lowercase(), "error names the dup case: ${ex.message}")
    }
}
