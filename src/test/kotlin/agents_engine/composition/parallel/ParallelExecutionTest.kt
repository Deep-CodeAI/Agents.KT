package agents_engine.composition.parallel

import agents_engine.composition.pipeline.then
import agents_engine.core.agent
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MODEL = "gpt-oss:120b-cloud"
private const val HOST  = "localhost"
private const val PORT  = 11434

class ParallelExecutionTest {

    // --- Lambda execution ---

    @Test
    fun `two lambda agents execute in parallel and return list`() {
        val upper = agent<String, String>("upper") {
            skills { skill<String, String>("upper", "Uppercase") { implementedBy { it.uppercase() } } }
        }
        val lower = agent<String, String>("lower") {
            skills { skill<String, String>("lower", "Lowercase") { implementedBy { it.lowercase() } } }
        }

        val result = (upper / lower)("Hello")
        assertEquals(listOf("HELLO", "hello"), result)
    }

    @Test
    fun `three lambda agents all receive the same input`() {
        val inputs = mutableListOf<String>()

        val a = agent<String, String>("a") {
            skills { skill<String, String>("a", "A") { implementedBy { inputs.add(it); "a" } } }
        }
        val b = agent<String, String>("b") {
            skills { skill<String, String>("b", "B") { implementedBy { inputs.add(it); "b" } } }
        }
        val c = agent<String, String>("c") {
            skills { skill<String, String>("c", "C") { implementedBy { inputs.add(it); "c" } } }
        }

        val result = (a / b / c)("same")
        assertEquals(listOf("a", "b", "c"), result)
        assertEquals(listOf("same", "same", "same"), inputs)
    }

    @Test
    fun `parallel results feed into aggregator via pipeline`() {
        val a = agent<String, Int>("a") {
            skills { skill<String, Int>("a", "Length") { implementedBy { it.length } } }
        }
        val b = agent<String, Int>("b") {
            skills { skill<String, Int>("b", "Words") { implementedBy { it.split(" ").size } } }
        }
        val sum = agent<List<Int>, Int>("sum") {
            skills { skill<List<Int>, Int>("sum", "Sum") { implementedBy { it.sum() } } }
        }

        val pipeline = (a / b) then sum
        // "hello world" → [11, 2] → 13
        assertEquals(13, pipeline("hello world"))
    }

    // --- Mock LLM execution ---

    @Test
    fun `two agentic agents execute in parallel`() {
        val mockA = ModelClient { _ -> LlmResponse.Text("response-a") }
        val mockB = ModelClient { _ -> LlmResponse.Text("response-b") }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mockA }
            skills { skill<String, String>("sa", "Skill A") { tools() } }
        }
        val b = agent<String, String>("b") {
            model { ollama("llama3"); client = mockB }
            skills { skill<String, String>("sb", "Skill B") { tools() } }
        }

        val result = (a / b)("input")
        assertEquals(listOf("response-a", "response-b"), result)
    }

    @Test
    fun `agentic agent with tool calls works in parallel`() {
        val responsesA = ArrayDeque<LlmResponse>()
        responsesA.add(LlmResponse.ToolCalls(listOf(ToolCall("reverse", mapOf("t" to "abc")))))
        responsesA.add(LlmResponse.Text("cba"))
        val mockA = ModelClient { _ -> responsesA.removeFirst() }

        val mockB = ModelClient { _ -> LlmResponse.Text("plain") }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mockA }
            tools { tool("reverse") { args -> args["t"].toString().reversed() } }
            skills { skill<String, String>("sa", "Reverse") { tools("reverse") } }
        }
        val b = agent<String, String>("b") {
            model { ollama("llama3"); client = mockB }
            skills { skill<String, String>("sb", "Plain") { tools() } }
        }

        val result = (a / b)("input")
        assertEquals(listOf("cba", "plain"), result)
    }

    @Test
    fun `parallel agentic agents feed into lambda aggregator`() {
        val mockA = ModelClient { _ -> LlmResponse.Text("10") }
        val mockB = ModelClient { _ -> LlmResponse.Text("20") }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mockA }
            skills { skill<String, String>("sa", "Score A") { tools() } }
        }
        val b = agent<String, String>("b") {
            model { ollama("llama3"); client = mockB }
            skills { skill<String, String>("sb", "Score B") { tools() } }
        }
        val merge = agent<List<String>, String>("merge") {
            skills { skill<List<String>, String>("merge", "Merge") {
                implementedBy { it.joinToString("+") }
            }}
        }

        val pipeline = (a / b) then merge
        assertEquals("10+20", pipeline("input"))
    }

    @Test
    fun `onSkillChosen fires for each parallel agent`() {
        val chosen = mutableListOf<String>()
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

        (a / b)("input")
        assertEquals(listOf("skill-a", "skill-b"), chosen)
    }

    // --- Real LLM integration ---

    @Test
    fun `parallel agents with real LLM produce independent results`() {
        val frenchAgent = agent<String, String>("french") {
            prompt("Translate the given text to French. Reply with ONLY the translation.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("translate-fr", "Translate to French") { tools() } }
        }
        val germanAgent = agent<String, String>("german") {
            prompt("Translate the given text to German. Reply with ONLY the translation.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("translate-de", "Translate to German") { tools() } }
        }

        val results = (frenchAgent / germanAgent)("Good morning")
        println("French: ${results[0]}")
        println("German: ${results[1]}")

        assertEquals(2, results.size)
        // French should contain "bonjour" or "bon matin"
        assertTrue(results[0].lowercase().let { it.contains("bonjour") || it.contains("bon") },
            "Expected French, got: ${results[0]}")
        // German should contain "guten morgen" or similar
        assertTrue(results[1].lowercase().let { it.contains("guten") || it.contains("morgen") },
            "Expected German, got: ${results[1]}")
    }

    @Test
    fun `parallel then aggregator pipeline with real LLM`() {
        val pros = agent<String, String>("pros") {
            prompt("List ONE pro (advantage) of the given topic. Reply with a single short sentence, nothing else.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("pros", "List a pro") { tools() } }
        }
        val cons = agent<String, String>("cons") {
            prompt("List ONE con (disadvantage) of the given topic. Reply with a single short sentence, nothing else.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("cons", "List a con") { tools() } }
        }
        val summarizer = agent<List<String>, String>("summarizer") {
            prompt("You receive a list of arguments. Combine them into a single balanced summary sentence. Reply with ONLY the summary.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<List<String>, String>("summarize", "Summarize arguments") { tools() } }
        }

        val pipeline = (pros / cons) then summarizer
        val result = pipeline("Remote work")
        println("Pros/Cons summary: $result")

        assertTrue(result.isNotBlank(), "Summary should not be empty")
        assertTrue(result.length > 10, "Summary should be a real sentence, got: $result")
    }
}
