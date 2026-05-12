package agents_engine.composition.wrap

import agents_engine.composition.pipeline.Pipeline
import agents_engine.composition.pipeline.then
import agents_engine.core.agent
import agents_engine.core.skill
import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.assertThrows

// Tests for #1698 — `>>` (wrap) teacher-student prompt override operator.
//
// `teacher wrap student` runs the teacher first to produce a string, then
// invokes the student with that string as its system prompt for that one
// call. The student's baked-in prompt is restored afterward.
//
// Two framings are exercised:
// - Education: the teacher tells the student what task to perform.
// - Security: the override is in effect ONLY during the call; the student
//   cannot retain the override across invocations.
class WrapTest {

    // Reference Fibonacci impl for assertions.
    private fun fib(n: Int): Long {
        require(n >= 0)
        var a = 0L; var b = 1L
        repeat(n) { val t = a + b; a = b; b = t }
        return a
    }

    /**
     * The headline test: agent A teaches agent B to calculate fib(10).
     *
     * - Teacher: emits a system prompt instructing the student to use the
     *   `fib` tool to compute Fibonacci for the given integer.
     * - Student: agentic with a real `fib` tool, driven by a stub
     *   [ModelClient] that emits a tool call when the system prompt mentions
     *   Fibonacci, then returns the tool's result as final text.
     *
     * Verifies:
     *   1. Pipeline output is "55" (fib(10)).
     *   2. Stub model client saw the teacher's instruction in the system
     *      message (not the student's baked-in default).
     *   3. After the call, `student.prompt` is restored to its default —
     *      the override is observation-only for the duration of the call.
     */
    @Test
    fun `wrap operator —teacher makes student compute fibonacci(10)`() {
        val teacher = agent<String, String>("teacher") {
            skills {
                skill<String, String>("instruct", "Produce a system prompt for the student") {
                    implementedBy { _ ->
                        "You are a Fibonacci calculator. " +
                            "Use the `fib` tool to compute fib(n) for the given integer n. " +
                            "Return ONLY the final number, with no commentary."
                    }
                }
            }
        }

        val capturedSystemPrompts = mutableListOf<String>()
        val stubModelClient = ModelClient { messages ->
            // Capture every system message the loop sends, in order.
            messages.firstOrNull { it.role == "system" }?.let { capturedSystemPrompts += it.content }

            val systemContent = messages.firstOrNull { it.role == "system" }?.content.orEmpty()
            val toolMessage = messages.firstOrNull { it.role == "tool" }

            when {
                toolMessage != null -> LlmResponse.Text(toolMessage.content)  // final answer = tool result
                "Fibonacci" in systemContent -> {
                    val userInput = messages.first { it.role == "user" }.content.trim()
                    val n = userInput.toIntOrNull() ?: fail("user input must be an integer; got '$userInput'")
                    LlmResponse.ToolCalls(listOf(
                        ToolCall(name = "fib", arguments = mapOf("n" to n)),
                    ))
                }
                else -> LlmResponse.Text("(no task)")
            }
        }

        val student = agent<String, String>("student") {
            prompt("DEFAULT — should be overridden by the teacher during the call")
            model { ollama("stub"); client = stubModelClient }
            tools {
                tool("fib", "Compute the nth Fibonacci number. Argument: n (integer ≥ 0).") { args ->
                    val n = (args["n"] as Number).toInt()
                    fib(n).toString()
                }
            }
            skills {
                skill<String, String>("compute", "Compute Fibonacci via the fib tool") { tools("fib") }
            }
        }

        val pipeline: Pipeline<String, String> = teacher wrap student
        val result = pipeline("10")

        // 1. Output is fib(10).
        assertEquals("55", result)

        // 2. The system prompt the stub saw was the teacher's, not the student's default.
        assertTrue(capturedSystemPrompts.isNotEmpty(), "stub model client must have been called at least once")
        capturedSystemPrompts.forEach { sys ->
            assertTrue(
                "Fibonacci calculator" in sys,
                "every system prompt in the call should carry the teacher's instruction, got: $sys",
            )
            assertFalse(
                "DEFAULT" in sys,
                "the student's baked-in 'DEFAULT' prompt must not appear during the call: $sys",
            )
        }

        // 3. After the call, the student's prompt is restored. The override
        //    is per-call only — single-placement makes this safe under the
        //    framework's existing contract.
        assertTrue(
            "DEFAULT" in student.prompt,
            "after the call, student.prompt must be restored to the baked-in value, got: ${student.prompt}",
        )
    }

    @Test
    fun `wrap operator —student observes teacher's output for the call only, default restored after`() {
        val teacher = agent<String, String>("teacher") {
            skills {
                skill<String, String>("emit", "Emit an instruction") {
                    implementedBy { "instruction-for-${it}" }
                }
            }
        }

        // Observation: the student's promptable view at runtime is the teacher's
        // output, verified via the stub model client's lens.
        var promptSeenAtCall: String? = null
        val student = agent<String, String>("student") {
            prompt("baked-in-default")
            model {
                ollama("stub")
                client = ModelClient { msgs ->
                    promptSeenAtCall = msgs.firstOrNull { it.role == "system" }?.content
                    LlmResponse.Text("ok")
                }
            }
            skills { skill<String, String>("op", "op") { tools() } }
        }

        val pipeline = teacher wrap student
        pipeline("hi")

        // The system message composes the agent prompt with the skill's
        // auto-generated description (toLlmContext() — adds "## Skill: ..."
        // sections). We assert the teacher's contribution is at the head;
        // the skill description tail is framework-owned.
        assertNotNull(promptSeenAtCall, "stub model client must have been invoked")
        assertTrue(
            promptSeenAtCall!!.startsWith("instruction-for-hi"),
            "system prompt should start with the teacher's output, got: $promptSeenAtCall",
        )
        assertEquals("baked-in-default", student.prompt, "after wrap call, student.prompt must be the baked-in value")
    }

    @Test
    fun `wrap operator —marks both agents placed (single-placement contract)`() {
        val teacher = agent<String, String>("teacher") {
            skills { skill<String, String>("emit", "emit") { implementedBy { "p" } } }
        }
        val student = agent<String, String>("student") {
            skills { skill<String, String>("op", "op") { implementedBy { "x" } } }
        }

        // Form a pipeline — placement happens at composition time.
        val pipeline = teacher wrap student
        assertNotNull(pipeline, "wrap must return a Pipeline")

        // Reusing either agent in a second composition must throw — this is
        // the same single-placement guarantee that `then` enforces.
        val student2 = agent<String, String>("student2") {
            skills { skill<String, String>("op", "op") { implementedBy { it } } }
        }
        assertThrows<IllegalArgumentException> {
            teacher wrap student2
        }
    }

    @Test
    fun `wrap operator —composes as a Pipeline, chainable via then downstream`() {
        val teacher = agent<String, String>("teacher") {
            skills {
                skill<String, String>("emit", "Emit instruction") {
                    implementedBy { _ -> "TEACHER-PROMPT" }
                }
            }
        }
        val student = agent<String, String>("student") {
            prompt("default")
            model { ollama("stub"); client = ModelClient { LlmResponse.Text("STUDENT-OUT") } }
            skills { skill<String, String>("op", "op") { tools() } }
        }
        val tail = agent<String, String>("tail") {
            skills {
                skill<String, String>("decorate", "Wrap the answer") {
                    implementedBy { "<$it>" }
                }
            }
        }

        // `(wrap-pipeline) then tail` uses the existing `Pipeline.then(Agent)`
        // overload — verifies wrap's Pipeline output is a first-class
        // composition citizen downstream.
        val full = (teacher wrap student) then tail
        assertEquals("<STUDENT-OUT>", full("hi"))
    }
}
