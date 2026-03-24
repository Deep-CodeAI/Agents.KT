package agents_engine.composition.loop

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

class LoopAgenticIntegrationTest {

    // --- Mock LLM ---

    @Test
    fun `agentic agent loops until condition met`() {
        var callCount = 0
        val mock = ModelClient { _ ->
            callCount++
            LlmResponse.Text("${callCount * 10}")
        }

        val scorer = agent<String, Int>("scorer") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, Int>("score", "Score quality") {
                tools()
                transformOutput { it.trim().toInt() }
            }}
        }

        val loop = scorer.loop { result ->
            if (result >= 30) null else "score again, current: $result"
        }

        val result = loop("evaluate")
        assertEquals(30, result)
        assertEquals(3, callCount)
    }

    @Test
    fun `agentic loop with tool calls each iteration`() {
        val toolLog = mutableListOf<String>()
        var iteration = 0

        val mock = ModelClient { _ ->
            iteration++
            if (iteration % 2 == 1) {
                // odd calls: use tool
                LlmResponse.ToolCalls(listOf(ToolCall("increment", mapOf("n" to "$iteration"))))
            } else {
                // even calls: return result after tool
                LlmResponse.Text("$iteration")
            }
        }

        val worker = agent<String, Int>("worker") {
            model { ollama("llama3"); client = mock }
            tools { tool("increment", "Increment a number") { args -> toolLog.add(args["n"].toString()); "ok" } }
            skills { skill<String, Int>("work", "Do iterative work") {
                tools("increment")
                transformOutput { it.trim().toInt() }
            }}
        }

        val loop = worker.loop { result ->
            if (result >= 4) null else "continue from $result"
        }

        val result = loop("start")
        assertEquals(4, result)
        assertTrue(toolLog.isNotEmpty(), "Tools should have been called during loop iterations")
    }

    @Test
    fun `loop feeds agentic output back as input`() {
        val inputs = mutableListOf<String>()
        val mock = ModelClient { msgs ->
            val userMsg = msgs.last { it.role == "user" }.content
            inputs.add(userMsg)
            val n = Regex("\\d+").find(userMsg)?.value?.toInt() ?: 0
            LlmResponse.Text("${n + 1}")
        }

        val agent = agent<String, String>("inc") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("inc", "Increment number in text") { tools() } }
        }

        val loop = agent.loop { result ->
            val n = result.trim().toInt()
            if (n >= 3) null else "number: $n"
        }

        val result = loop("number: 0")
        assertEquals("3", result)
        assertEquals("number: 0", inputs[0])
        assertEquals("number: 1", inputs[1])
        assertEquals("number: 2", inputs[2])
    }

    @Test
    fun `agentic pipeline loops until quality threshold`() {
        var draftNum = 0
        val drafterMock = ModelClient { _ -> draftNum++; LlmResponse.Text("draft-v$draftNum") }

        var lastScore = 0
        val scorerMock = ModelClient { _ -> lastScore += 35; LlmResponse.Text("$lastScore") }

        val drafter = agent<String, String>("drafter") {
            model { ollama("llama3"); client = drafterMock }
            skills { skill<String, String>("draft", "Write a draft") { tools() } }
        }

        val scorer = agent<String, Int>("scorer") {
            model { ollama("llama3"); client = scorerMock }
            skills { skill<String, Int>("score", "Score the draft 0-100") {
                tools()
                transformOutput { it.trim().toInt() }
            }}
        }

        data class Attempt(val draft: String, val score: Int)

        // Pipeline: draft → score, then loop if score < 70
        val pipeline = drafter then scorer

        var bestDraft = ""
        val loop = pipeline.loop { score ->
            if (score >= 70) null else "revise, last score: $score"
        }

        val finalScore = loop("write about Kotlin")
        assertEquals(70, finalScore)
        assertEquals(2, draftNum, "Should have drafted twice before reaching threshold")
    }

    @Test
    fun `onSkillChosen fires each loop iteration`() {
        val chosen = mutableListOf<String>()
        var n = 0
        val mock = ModelClient { _ -> n++; LlmResponse.Text("$n") }

        val agent = agent<String, Int>("agent") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, Int>("process", "Process input") {
                tools()
                transformOutput { it.trim().toInt() }
            }}
            onSkillChosen { chosen.add(it) }
        }

        val loop = agent.loop { result -> if (result >= 3) null else "go" }
        loop("start")

        assertEquals(listOf("process", "process", "process"), chosen)
    }

    @Test
    fun `loop composable in pipeline with agentic agents`() {
        val prepareMock = ModelClient { _ -> LlmResponse.Text("0") }
        var n = 0
        val incMock = ModelClient { _ -> n++; LlmResponse.Text("$n") }
        val wrapMock = ModelClient { _ -> LlmResponse.Text("done: $n") }

        val prepare = agent<String, String>("prepare") {
            model { ollama("llama3"); client = prepareMock }
            skills { skill<String, String>("prepare", "Prepare") { tools() } }
        }

        val inc = agent<String, String>("inc") {
            model { ollama("llama3"); client = incMock }
            skills { skill<String, String>("inc", "Increment") { tools() } }
        }

        val wrap = agent<String, String>("wrap") {
            model { ollama("llama3"); client = wrapMock }
            skills { skill<String, String>("wrap", "Wrap result") { tools() } }
        }

        val loop = inc.loop { result ->
            val v = result.trim().toIntOrNull() ?: 0
            if (v >= 3) null else "next"
        }

        val pipeline = prepare then loop then wrap
        val result = pipeline("input")
        assertEquals("done: 3", result)
    }

    // --- Real LLM ---

    @Test
    fun `iterative refinement loop with real LLM`() {
        val iterations = mutableListOf<String>()

        val refiner = agent<String, String>("refiner") {
            prompt("You are a text improver. Make the given text more formal and professional. Reply with ONLY the improved text, nothing else. Keep it to one sentence.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("refine", "Improve text formality") { tools() } }
        }

        var count = 0
        val loop = refiner.loop { result ->
            count++
            iterations.add(result)
            if (count >= 3) null else "Make this more formal: $result"
        }

        val result = loop("hey dude whats up wanna grab some food")
        println("Refinement iterations:")
        iterations.forEachIndexed { i, text -> println("  ${i + 1}: $text") }

        assertEquals(3, iterations.size)
        // Final result should be noticeably more formal than input
        assertTrue(result.length > 10, "Expected a real sentence, got: $result")
        assertTrue(
            !result.lowercase().contains("dude") && !result.lowercase().contains("whats up"),
            "Expected formal text without slang, got: $result"
        )
    }
}
