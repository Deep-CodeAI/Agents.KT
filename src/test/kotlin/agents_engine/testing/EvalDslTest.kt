package agents_engine.testing

import agents_engine.core.agent
import agents_engine.generation.Generable
import agents_engine.generation.Guide
import agents_engine.generation.toLlmInput
import agents_engine.model.LlmResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2493 — declarative eval cases with typed assertions. Pins:
 *
 * 1. `eval { input(...); expect { ... } }` builds a case with typed
 *    predicates over `OUT`.
 * 2. Multiple expectations compose — all must pass.
 * 3. Snapshot mode pins a known typed output structurally.
 * 4. Failures carry diagnostic messages naming the failing label.
 * 5. Suite mode bundles cases.
 * 6. Composition with DeterministicModelClient — full no-network eval.
 */
class EvalDslTest {

    @Test
    fun `passing eval case with typed predicate`() {
        val mock = DeterministicModelClient(LlmResponse.Text("hello"))
        val a = agent<String, String>("greet") {
            model { ollama("t"); client = mock }
            skills { skill<String, String>("s", "") { tools() } }
        }
        val case = eval<String, String>("greet-says-hello") {
            input("hi")
            expect("contains hello") { it.contains("hello") }
        }
        val result = case.run(a)
        assertTrue(result.passed, result.failureMessage)
        assertEquals("hello", result.output)
    }

    @Test
    fun `multiple expectations all must pass`() {
        // Two cases against fresh agents — DeterministicModelClient is single-use per agent.
        fun greetAgent(text: String) = agent<String, String>("greet") {
            model { ollama("t"); client = DeterministicModelClient(LlmResponse.Text(text)) }
            skills { skill<String, String>("s", "") { tools() } }
        }
        val passing = eval<String, String>("multi-pass") {
            input("hi")
            expect("nonempty") { it.isNotEmpty() }
            expect("starts with hello") { it.startsWith("hello") }
        }
        assertTrue(passing.run(greetAgent("hello world")).passed)

        val failing = eval<String, String>("multi-fail") {
            input("hi")
            expect("nonempty") { it.isNotEmpty() }
            expect("starts with goodbye") { it.startsWith("goodbye") }
        }
        val result = failing.run(greetAgent("hello world"))
        assertFalse(result.passed)
        assertEquals(2, result.outcomes.size)
        assertTrue(result.outcomes[0].passed, "first expectation passed")
        assertFalse(result.outcomes[1].passed, "second expectation failed")
        assertTrue("starts with goodbye" in result.failureMessage!!)
    }

    @Test
    fun `agent invocation error captured as hard failure`() {
        val mock = DeterministicModelClient()  // empty script → exhaustion
        val a = agent<String, String>("explode") {
            model { ollama("t"); client = mock }
            skills { skill<String, String>("s", "") { tools() } }
        }
        val case = eval<String, String>("explode") {
            input("trigger")
            expect("never reached") { true }
        }
        val result = case.run(a)
        assertFalse(result.passed)
        assertNotNull(result.invocationError, "agent throw captured")
        assertTrue("explode" in result.failureMessage!!, "case name in message")
    }

    @Test
    fun `snapshot expectation passes when toLlmInput output matches`() {
        val mock = DeterministicModelClient(LlmResponse.Text("""{"text":"Hello","approved":true}"""))
        val a = agent<String, Review>("review") {
            model { ollama("t"); client = mock }
            skills { skill<String, Review>("s", "") { tools() } }
        }
        // The expected snapshot is the toLlmInput rendering of the Review
        // the model returned. For text-typed outputs the LLM JSON is the
        // raw text we shouldn't render through toLlmInput; for typed @Generable
        // outputs the parser deserializes the JSON first and toLlmInput
        // re-serializes structurally.
        val sample = Review(text = "Hello", approved = true)
        val expectedSnapshot = toLlmInput(sample)
        val case = eval<String, Review>("review-snapshot") {
            input("review")
            expectSnapshot(snapshot = expectedSnapshot)
        }
        val result = case.run(a)
        assertTrue(result.passed, result.failureMessage)
    }

    @Test
    fun `snapshot expectation fails with a typed diff on mismatch`() {
        val mock = DeterministicModelClient(LlmResponse.Text("""{"text":"Goodbye","approved":false}"""))
        val a = agent<String, Review>("review") {
            model { ollama("t"); client = mock }
            skills { skill<String, Review>("s", "") { tools() } }
        }
        val wrongSnapshot = toLlmInput(Review(text = "Hello", approved = true))
        val case = eval<String, Review>("review-snapshot-mismatch") {
            input("review")
            expectSnapshot(snapshot = wrongSnapshot)
        }
        val result = case.run(a)
        assertFalse(result.passed)
        val msg = result.failureMessage!!
        assertTrue("snapshot mismatch" in msg, "message names the kind of failure: $msg")
        assertTrue("expected:" in msg && "actual:" in msg, "diff shape preserved: $msg")
    }

    @Test
    fun `expectFieldEquals matches a single field without spelling out full snapshot`() {
        val mock = DeterministicModelClient(LlmResponse.Text("""{"text":"Hi","approved":true}"""))
        val a = agent<String, Review>("review") {
            model { ollama("t"); client = mock }
            skills { skill<String, Review>("s", "") { tools() } }
        }
        val case = eval<String, Review>("approved-true") {
            input("any")
            expectFieldEquals("approved", true)
        }
        val result = case.run(a)
        assertTrue(result.passed, result.failureMessage)
    }

    @Test
    fun `eval suite runs all cases and reports per-case results`() {
        val mockA = DeterministicModelClient(LlmResponse.Text("first"))
        val agentA = agent<String, String>("a") {
            model { ollama("t"); client = mockA }
            skills { skill<String, String>("s", "") { tools() } }
        }
        val suite = evalSuite("greeting-suite") {
            + eval<String, String>("nonempty") {
                input("hi")
                expect("nonempty") { it.isNotEmpty() }
            }
            + eval<String, String>("equals first") {
                input("hi")
                expect("eq first") { it == "first" }
            }
        }
        // Suite only handles homogeneous case types — both cases above are <String, String>.
        // Run; expect the second to fail because the script only produces one response.
        val result = suite.runAll(agentA)
        assertEquals("greeting-suite", result.name)
        // First case ran; second case exhausted the script.
        val outcomes = result.results
        assertEquals(2, outcomes.size)
    }

    @Test
    fun `eval case requires an input call`() {
        val ex = kotlin.runCatching {
            eval<String, String>("missing-input") {
                expect("any") { true }
            }
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("input" in ex.message!!, "error names the missing call: ${ex.message}")
    }

    @Test
    fun `eval case requires at least one expect block`() {
        val ex = kotlin.runCatching {
            eval<String, String>("missing-expect") {
                input("anything")
            }
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("expect" in ex.message!!, "error names the missing call: ${ex.message}")
    }

    @Generable("A repository review summary used by the eval doc example.")
    data class Review(
        @Guide("Plain-text body of the review.") val text: String,
        @Guide("Whether the review approves the change.") val approved: Boolean,
    )
}
