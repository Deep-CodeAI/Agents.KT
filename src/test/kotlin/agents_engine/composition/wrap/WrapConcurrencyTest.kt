package agents_engine.composition.wrap

import agents_engine.core.Agent
import agents_engine.core.agent
import agents_engine.core.skill
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #1707/#3 — `wrap` must not produce cross-talk between concurrent invocations.
 *
 * v0.4.4 implemented prompt override by mutating `student.prompt` for the
 * duration of one call and restoring it after. That's safe only when the
 * pipeline is invoked sequentially; concurrent invocations race on the
 * mutable field and one caller can see another's prompt mid-call.
 *
 * This test launches N parallel pipeline invocations with distinguishable
 * teacher outputs and asserts each invocation's stub `ModelClient` sees
 * the prompt that ITS teacher produced — never another lane's. The fix
 * threads the effective prompt through invocation context instead of
 * mutating the field; this test fails red on the v0.4.4 implementation
 * and passes once #1707 lands.
 */
class WrapConcurrencyTest {

    @Test
    fun `parallel wrap invocations do not bleed prompts between coroutines`() {
        // Capture (teacher's emitted prompt, prompt seen by student) per call.
        // Concurrent map so the stub model client can record from N threads.
        val observed = ConcurrentLinkedQueue<Pair<String, String>>()
        val gate = CountDownLatch(1)  // hold all coroutines at the entry so they actually overlap

        val teacher = agent<String, String>("teacher") {
            skills {
                skill<String, String>("brief", "Tag the prompt with the input") {
                    implementedBy { input ->
                        // Hold here briefly so we GUARANTEE the wrap-and-invoke
                        // calls overlap across coroutines. (gate.await ensures
                        // every lane has started before any teacher returns.)
                        gate.await(2, TimeUnit.SECONDS)
                        "TEACHER-PROMPT-FOR-$input"
                    }
                }
            }
        }

        val student = agent<String, String>("student") {
            prompt("BAKED-IN-DEFAULT")
            model {
                ollama("stub")
                client = ModelClient { msgs ->
                    val systemSeen = msgs.firstOrNull { it.role == "system" }?.content.orEmpty()
                    val userSeen = msgs.first { it.role == "user" }.content
                    observed += userSeen to systemSeen
                    LlmResponse.Text("ok")
                }
            }
            skills { skill<String, String>("answer", "Reply") { tools() } }
        }

        val pipeline = teacher wrap student

        // Launch 8 parallel invocations with distinct inputs.
        runBlocking {
            val deferreds = (1..8).map { idx ->
                async(kotlinx.coroutines.Dispatchers.Default) {
                    pipeline.invokeSuspend("input-$idx")
                }
            }
            // Open the gate once all 8 are inside their teacher's
            // implementedBy lambda — guarantees real concurrency rather
            // than coincidental sequential dispatch.
            Thread.sleep(50)
            gate.countDown()
            deferreds.awaitAll()
        }

        // Every recorded (user-input, system-prompt) pair must agree:
        // the system prompt must mention the SAME input the user message
        // carried. Cross-talk would put input-3 in user message and
        // TEACHER-PROMPT-FOR-input-5 in the system message.
        assertEquals(8, observed.size, "all 8 invocations must have reached the stub")
        observed.forEach { (userInput, systemPrompt) ->
            // The system message is `<teacher's prompt>\n\n<skill auto-description>`,
            // so we check the teacher's contribution is at the head — not a
            // substring elsewhere — to detect cross-talk.
            val expectedHead = "TEACHER-PROMPT-FOR-$userInput"
            assertTrue(
                systemPrompt.startsWith(expectedHead),
                "cross-talk: user input '$userInput' saw system prompt '$systemPrompt' (expected start with '$expectedHead')",
            )
        }
    }

    @Test
    fun `concurrent wrap and direct agent invocation do not race on prompt`() {
        // Different angle: while a wrap-pipeline is mid-call, someone else
        // invokes the same student directly. Direct call must see the
        // baked-in prompt, NOT the wrap override.
        val gate = CountDownLatch(1)
        val released = CountDownLatch(2)
        val observedDirect = ConcurrentLinkedQueue<String>()
        val observedWrapped = ConcurrentLinkedQueue<String>()

        val teacher = agent<String, String>("teacher") {
            skills {
                skill<String, String>("brief", "Emit slow prompt") {
                    implementedBy {
                        gate.await(2, TimeUnit.SECONDS)
                        "WRAPPED-PROMPT-X"
                    }
                }
            }
        }

        lateinit var studentRef: Agent<String, String>
        studentRef = agent<String, String>("student") {
            prompt("BAKED-IN-DEFAULT")
            model {
                ollama("stub")
                client = ModelClient { msgs ->
                    val sys = msgs.firstOrNull { it.role == "system" }?.content.orEmpty()
                    val userText = msgs.first { it.role == "user" }.content
                    when (userText) {
                        "via-wrap" -> observedWrapped += sys
                        "via-direct" -> observedDirect += sys
                    }
                    released.countDown()
                    LlmResponse.Text("ok")
                }
            }
            skills { skill<String, String>("answer", "Reply") { tools() } }
        }

        val pipeline = teacher wrap studentRef

        runBlocking {
            val a = async(kotlinx.coroutines.Dispatchers.Default) {
                pipeline.invokeSuspend("via-wrap")
            }
            // Briefly let the wrap path start (teacher waits at gate).
            Thread.sleep(50)
            val b = async(kotlinx.coroutines.Dispatchers.Default) {
                // Direct invocation — must see the baked-in default,
                // regardless of what the parallel wrap call is doing.
                studentRef.invokeSuspend("via-direct")
            }
            // Both coroutines are now in flight. Release the gate; teacher
            // returns; pipeline finishes; direct call finishes.
            Thread.sleep(50)
            gate.countDown()
            a.await()
            b.await()
        }

        assertEquals(1, observedWrapped.size, "wrapped lane must have run once")
        assertEquals(1, observedDirect.size, "direct lane must have run once")
        assertTrue(
            observedWrapped.first().startsWith("WRAPPED-PROMPT-X"),
            "wrap call should see teacher's prompt at the head; got: ${observedWrapped.first()}",
        )
        assertTrue(
            "BAKED-IN-DEFAULT" in observedDirect.first(),
            "direct call must see baked-in default while wrap is mid-flight; got: ${observedDirect.first()}",
        )
    }
}
