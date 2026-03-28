package agents_engine.composition.forum

import agents_engine.composition.pipeline.then
import agents_engine.core.agent
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MODEL = "gpt-oss:120b-cloud"
private const val HOST  = "localhost"
private const val PORT  = 11434

class ForumExecutionTest {

    // --- Lambda execution ---

    @Test
    fun `forum solves math - captain delivers final answer`() {
        data class Problem(val a: Int, val b: Int, val c: Int)

        val adder = agent<Problem, Int>("adder") {
            skills { skill<Problem, Int>("add", "Sum all") { implementedBy { it.a + it.b + it.c } } }
        }
        val multiplier = agent<Problem, Int>("multiplier") {
            skills { skill<Problem, Int>("mul", "Multiply all") { implementedBy { it.a * it.b * it.c } } }
        }
        val solver = agent<Problem, Int>("solver") {
            skills { skill<Problem, Int>("solve", "a*b+c") { implementedBy { it.a * it.b + it.c } } }
        }

        val forum = adder * multiplier * solver
        // captain (solver): 5 * 7 + 3 = 38
        assertEquals(38, forum(Problem(5, 7, 3)))
    }

    @Test
    fun `all participants execute`() {
        val executed = java.util.concurrent.CopyOnWriteArrayList<String>()

        val voter1 = agent<Int, Int>("voter1") {
            skills { skill<Int, Int>("v1", "V1") { implementedBy { executed.add("voter1"); it * 2 } } }
        }
        val voter2 = agent<Int, Int>("voter2") {
            skills { skill<Int, Int>("v2", "V2") { implementedBy { executed.add("voter2"); it * 3 } } }
        }
        val captain = agent<Int, Int>("captain") {
            skills { skill<Int, Int>("decide", "Decide") { implementedBy { executed.add("captain"); it * 10 } } }
        }

        val result = (voter1 * voter2 * captain)(5)

        assertEquals(50, result)
        assertTrue("voter1" in executed, "voter1 should have executed")
        assertTrue("voter2" in executed, "voter2 should have executed")
        assertTrue("captain" in executed, "captain should have executed")
    }

    @Test
    fun `all agents receive the same input`() {
        val inputs = java.util.concurrent.CopyOnWriteArrayList<Int>()

        val a = agent<Int, String>("a") {
            skills { skill<Int, String>("a", "A") { implementedBy { inputs.add(it); "a" } } }
        }
        val b = agent<Int, String>("b") {
            skills { skill<Int, String>("b", "B") { implementedBy { inputs.add(it); "b" } } }
        }
        val c = agent<Int, String>("c") {
            skills { skill<Int, String>("c", "C") { implementedBy { inputs.add(it); "c" } } }
        }

        (a * b * c)(42)

        assertEquals(3, inputs.size)
        assertTrue(inputs.all { it == 42 }, "All agents should receive 42, got: $inputs")
    }

    @Test
    fun `two-agent forum - single participant and captain`() {
        val participant = agent<Int, Int>("participant") {
            skills { skill<Int, Int>("calc", "Square") { implementedBy { it * it } } }
        }
        val captain = agent<Int, Int>("captain") {
            skills { skill<Int, Int>("decide", "Add one") { implementedBy { it + 1 } } }
        }

        val forum = participant * captain
        // captain: 5 + 1 = 6 (participant runs but output not used by captain)
        assertEquals(6, forum(5))
    }

    // --- Concurrency ---

    @Test
    fun `participants run concurrently`() {
        val a = agent<String, String>("a") {
            skills { skill<String, String>("a", "A") { implementedBy { Thread.sleep(200); "a" } } }
        }
        val b = agent<String, String>("b") {
            skills { skill<String, String>("b", "B") { implementedBy { Thread.sleep(200); "b" } } }
        }
        val c = agent<String, String>("c") {
            skills { skill<String, String>("c", "C") { implementedBy { Thread.sleep(200); "c" } } }
        }
        val captain = agent<String, String>("captain") {
            skills { skill<String, String>("captain", "Captain") { implementedBy { "result" } } }
        }

        val start = System.currentTimeMillis()
        val result = (a * b * c * captain)("go")
        val elapsed = System.currentTimeMillis() - start

        assertEquals("result", result)
        assertTrue(elapsed < 500, "Expected concurrent participants (~200ms), took ${elapsed}ms")
    }

    @Test
    fun `participants run on different threads`() {
        val threads = java.util.concurrent.ConcurrentHashMap<String, String>()

        val a = agent<String, String>("a") {
            skills { skill<String, String>("a", "A") { implementedBy { threads["a"] = Thread.currentThread().name; "a" } } }
        }
        val b = agent<String, String>("b") {
            skills { skill<String, String>("b", "B") { implementedBy { threads["b"] = Thread.currentThread().name; "b" } } }
        }
        val captain = agent<String, String>("captain") {
            skills { skill<String, String>("captain", "Captain") { implementedBy { "done" } } }
        }

        (a * b * captain)("go")

        assertEquals(2, threads.size)
        assertTrue(threads["a"] != threads["b"],
            "Participants should run on different threads: a=${threads["a"]}, b=${threads["b"]}")
    }

    // --- Pipeline composition ---

    @Test
    fun `agent then forum executes end to end`() {
        val parser = agent<String, Int>("parser") {
            skills { skill<String, Int>("parse", "Parse int") { implementedBy { it.trim().toInt() } } }
        }
        val doubler = agent<Int, Int>("doubler") {
            skills { skill<Int, Int>("double", "Double") { implementedBy { it * 2 } } }
        }
        val tripler = agent<Int, Int>("tripler") {
            skills { skill<Int, Int>("triple", "Triple") { implementedBy { it * 3 } } }
        }
        val captain = agent<Int, Int>("captain") {
            skills { skill<Int, Int>("captain", "Add 100") { implementedBy { it + 100 } } }
        }

        val pipeline = parser then (doubler * tripler * captain)
        // parse "10" → 10, captain: 10 + 100 = 110
        assertEquals(110, pipeline("10"))
    }

    @Test
    fun `pipeline then forum then agent executes end to end`() {
        val prep = agent<String, Int>("prep") {
            skills { skill<String, Int>("prep", "Length") { implementedBy { it.length } } }
        }
        val v1 = agent<Int, Int>("v1") {
            skills { skill<Int, Int>("v1", "V1") { implementedBy { it + 1 } } }
        }
        val v2 = agent<Int, Int>("v2") {
            skills { skill<Int, Int>("v2", "V2") { implementedBy { it + 2 } } }
        }
        val captain = agent<Int, Int>("captain") {
            skills { skill<Int, Int>("captain", "x10") { implementedBy { it * 10 } } }
        }
        val formatter = agent<Int, String>("fmt") {
            skills { skill<Int, String>("fmt", "Format") { implementedBy { "answer=$it" } } }
        }

        val pipeline = prep then (v1 * v2 * captain) then formatter
        // "hello".length=5, captain: 5*10=50, format: "answer=50"
        assertEquals("answer=50", pipeline("hello"))
    }

    // --- Mock LLM ---

    @Test
    fun `agentic agents execute in forum`() {
        val mockA = ModelClient { _ -> LlmResponse.Text("opinion-a") }
        val mockB = ModelClient { _ -> LlmResponse.Text("opinion-b") }
        val mockCaptain = ModelClient { _ -> LlmResponse.Text("verdict") }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mockA }
            skills { skill<String, String>("sa", "Opinion A") { tools() } }
        }
        val b = agent<String, String>("b") {
            model { ollama("llama3"); client = mockB }
            skills { skill<String, String>("sb", "Opinion B") { tools() } }
        }
        val captain = agent<String, String>("captain") {
            model { ollama("llama3"); client = mockCaptain }
            skills { skill<String, String>("decide", "Decide") { tools() } }
        }

        val result = (a * b * captain)("What is 2+2?")
        assertEquals("verdict", result)
    }

    @Test
    fun `onSkillChosen fires for all forum agents`() {
        val chosen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val mock = ModelClient { _ -> LlmResponse.Text("ok") }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("skill-a", "A") { tools() } }
            onSkillChosen { chosen.add(it) }
        }
        val b = agent<String, String>("b") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("skill-b", "B") { tools() } }
            onSkillChosen { chosen.add(it) }
        }
        val captain = agent<String, String>("captain") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("skill-captain", "Captain") { tools() } }
            onSkillChosen { chosen.add(it) }
        }

        (a * b * captain)("input")
        assertTrue(chosen.containsAll(listOf("skill-a", "skill-b", "skill-captain")),
            "All skills should fire: $chosen")
    }

    // --- onMentionEmitted ---

    @Test
    fun `onMentionEmitted tracks all debate contributions`() {
        val mentions = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Any?>>()

        val adder = agent<Int, Int>("adder") {
            skills { skill<Int, Int>("add", "Add 10") { implementedBy { it + 10 } } }
        }
        val multiplier = agent<Int, Int>("multiplier") {
            skills { skill<Int, Int>("mul", "x2") { implementedBy { it * 2 } } }
        }
        val captain = agent<Int, Int>("captain") {
            skills { skill<Int, Int>("decide", "Square") { implementedBy { it * it } } }
        }

        val forum = adder * multiplier * captain
        forum.onMentionEmitted { name, output -> mentions.add(name to output) }

        val result = forum(5)

        assertEquals(25, result)
        assertEquals(3, mentions.size)
        assertTrue(mentions.any { it.first == "adder" && it.second == 15 }, "adder: 5+10=15")
        assertTrue(mentions.any { it.first == "multiplier" && it.second == 10 }, "multiplier: 5*2=10")
        assertTrue(mentions.any { it.first == "captain" && it.second == 25 }, "captain: 5*5=25")
    }

    @Test
    fun `onMentionEmitted counter tracks debate progress`() {
        val counter = java.util.concurrent.atomic.AtomicInteger(0)

        val a = agent<String, String>("analyst") {
            skills { skill<String, String>("a", "Analyze") { implementedBy { "analysis of $it" } } }
        }
        val b = agent<String, String>("critic") {
            skills { skill<String, String>("b", "Criticize") { implementedBy { "critique of $it" } } }
        }
        val c = agent<String, String>("synthesizer") {
            skills { skill<String, String>("c", "Synthesize") { implementedBy { "synthesis of $it" } } }
        }
        val captain = agent<String, String>("captain") {
            skills { skill<String, String>("d", "Decide") { implementedBy { "verdict on $it" } } }
        }

        val forum = a * b * c * captain
        forum.onMentionEmitted { _, _ -> counter.incrementAndGet() }

        forum("topic")

        assertEquals(4, counter.get())
    }

    @Test
    fun `onMentionEmitted fires for two-agent forum`() {
        val mentions = mutableListOf<String>()

        val participant = agent<Int, Int>("participant") {
            skills { skill<Int, Int>("p", "P") { implementedBy { it + 1 } } }
        }
        val captain = agent<Int, Int>("captain") {
            skills { skill<Int, Int>("c", "C") { implementedBy { it * 10 } } }
        }

        val forum = participant * captain
        forum.onMentionEmitted { name, _ -> mentions.add(name) }

        forum(3)

        assertEquals(listOf("participant", "captain"), mentions)
    }

    @Test
    fun `onMentionEmitted works with agentic agents`() {
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        val mentions = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Any?>>()
        val mock = ModelClient { _ -> LlmResponse.Text("ok") }

        val a = agent<String, String>("expert-a") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("sa", "A") { tools() } }
        }
        val b = agent<String, String>("expert-b") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("sb", "B") { tools() } }
        }
        val captain = agent<String, String>("arbiter") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("decide", "Decide") { tools() } }
        }

        val forum = a * b * captain
        forum.onMentionEmitted { name, output ->
            counter.incrementAndGet()
            mentions.add(name to output)
        }

        forum("debate this")

        assertEquals(3, counter.get())
        assertTrue(mentions.any { it.first == "expert-a" })
        assertTrue(mentions.any { it.first == "expert-b" })
        assertTrue(mentions.any { it.first == "arbiter" })
    }

    @Test
    fun `onMentionEmitted works in pipeline context`() {
        val counter = java.util.concurrent.atomic.AtomicInteger(0)

        val prep = agent<String, Int>("prep") {
            skills { skill<String, Int>("prep", "Length") { implementedBy { it.length } } }
        }
        val v1 = agent<Int, Int>("v1") {
            skills { skill<Int, Int>("v1", "V1") { implementedBy { it + 1 } } }
        }
        val captain = agent<Int, Int>("captain") {
            skills { skill<Int, Int>("captain", "x10") { implementedBy { it * 10 } } }
        }
        val formatter = agent<Int, String>("fmt") {
            skills { skill<Int, String>("fmt", "Format") { implementedBy { "=$it" } } }
        }

        val forum = v1 * captain
        forum.onMentionEmitted { _, _ -> counter.incrementAndGet() }

        val pipeline = prep then forum then formatter
        val result = pipeline("hello")

        assertEquals("=50", result)
        assertEquals(2, counter.get())
    }

    // --- Live LLM ---

    @Tag("live-llm")
    @Test
    fun `forum agents debate math problem with real LLM`() {
        val optimist = agent<String, String>("optimist") {
            prompt("You solve math problems. Reply with ONLY the numeric answer, nothing else.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("solve", "Solve optimistically") { tools() } }
        }
        val pessimist = agent<String, String>("pessimist") {
            prompt("You solve math problems carefully. Reply with ONLY the numeric answer, nothing else.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("solve", "Solve carefully") { tools() } }
        }
        val captain = agent<String, String>("captain") {
            prompt("Compute the exact answer. Reply with ONLY the numeric answer, nothing else.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("decide", "Final answer") { tools() } }
        }

        val mentionCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val forum = optimist * pessimist * captain
        forum.onMentionEmitted { name, output ->
            val n = mentionCounter.incrementAndGet()
            println("  mention #$n by $name: $output")
        }

        val result = forum("What is 15 * 7 + 3?")
        println("Forum result: $result")

        assertEquals(3, mentionCounter.get(), "Expected 3 mentions (2 participants + captain)")
        assertTrue(result.trim().startsWith("108"), "Expected 108, got: $result")
    }

    @Tag("live-llm")
    @Test
    fun `antagonistic agents debate and captain resolves correctly`() {
        val mentionCounter = java.util.concurrent.atomic.AtomicInteger(0)
        val mentions = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

        val bull = agent<String, String>("bull") {
            prompt(
                """You are a BULL debater. You ALWAYS argue the POSITIVE/YES side, no matter what.
                  |Give a one-sentence argument. Start your response with "YES —".""".trimMargin()
            )
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("argue", "Argue YES") { tools() } }
        }
        val bear = agent<String, String>("bear") {
            prompt(
                """You are a BEAR debater. You ALWAYS argue the NEGATIVE/NO side, no matter what.
                  |Give a one-sentence argument. Start your response with "NO —".""".trimMargin()
            )
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("argue", "Argue NO") { tools() } }
        }
        val judge = agent<String, String>("judge") {
            prompt(
                """You are an impartial judge. Answer the question with the factually correct answer.
                  |Reply with ONLY "YES" or "NO" followed by a one-sentence explanation.""".trimMargin()
            )
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("decide", "Deliver verdict") { tools() } }
        }

        val forum = bull * bear * judge
        forum.onMentionEmitted { name, output ->
            val n = mentionCounter.incrementAndGet()
            mentions.add(name to output.toString())
            println("  mention #$n [$name]: $output")
        }

        val result = forum("Is 51 a prime number?")
        println("Verdict: $result")

        assertEquals(3, mentionCounter.get(), "Expected 3 mentions (bull + bear + judge)")

        val bullSaid = mentions.first { it.first == "bull" }.second
        assertTrue(bullSaid.uppercase().contains("YES"), "Bull should argue YES, got: $bullSaid")

        val bearSaid = mentions.first { it.first == "bear" }.second
        assertTrue(bearSaid.uppercase().contains("NO"), "Bear should argue NO, got: $bearSaid")

        // 51 = 3 * 17, NOT prime — judge must side with the bear
        assertTrue(result.uppercase().contains("NO"), "51 is not prime (3*17), judge should say NO, got: $result")
    }
}
