package agents_engine.composition.pipeline

import agents_engine.core.agent
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MODEL = "gpt-oss:120b-cloud"
private const val HOST  = "localhost"
private const val PORT  = 11434

class PipelineAgenticIntegrationTest {

    // --- Unit tests with mock LLM ---

    @Test
    fun `two agentic agents chain through pipeline`() {
        val mockA = ModelClient { _ -> LlmResponse.Text("HELLO") }
        val mockB = ModelClient { _ -> LlmResponse.Text("HELLO!") }

        val upper = agent<String, String>("upper") {
            model { ollama("llama3"); client = mockA }
            skills { skill<String, String>("upper", "Uppercase text") { tools() } }
        }
        val exclaim = agent<String, String>("exclaim") {
            model { ollama("llama3"); client = mockB }
            skills { skill<String, String>("exclaim", "Add exclamation mark") { tools() } }
        }

        val pipeline = upper then exclaim
        assertEquals("HELLO!", pipeline("hello"))
    }

    @Test
    fun `agentic agent followed by lambda agent in pipeline`() {
        val mock = ModelClient { _ -> LlmResponse.Text("hello world") }

        val generator = agent<String, String>("generator") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("generate", "Generate text") { tools() } }
        }
        val counter = agent<String, Int>("counter") {
            skills { skill<String, Int>("count", "Count words") {
                implementedBy { it.split(" ").size }
            }}
        }

        val pipeline = generator then counter
        assertEquals(2, pipeline("count words in response"))
    }

    @Test
    fun `lambda agent followed by agentic agent in pipeline`() {
        val mock = ModelClient { _ -> LlmResponse.Text("HELLO WORLD") }

        val preparer = agent<String, String>("preparer") {
            skills { skill<String, String>("prepare", "Prepare input") {
                implementedBy { "Uppercase this: $it" }
            }}
        }
        val llmAgent = agent<String, String>("llm") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("process", "Process with LLM") { tools() } }
        }

        val pipeline = preparer then llmAgent
        assertEquals("HELLO WORLD", pipeline("hello world"))
    }

    @Test
    fun `three agentic agents chain correctly`() {
        val log = mutableListOf<String>()
        val mockA = ModelClient { _ -> log.add("a"); LlmResponse.Text("step-a") }
        val mockB = ModelClient { _ -> log.add("b"); LlmResponse.Text("step-b") }
        val mockC = ModelClient { _ -> log.add("c"); LlmResponse.Text("step-c") }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mockA }
            skills { skill<String, String>("sa", "Step A") { tools() } }
        }
        val b = agent<String, String>("b") {
            model { ollama("llama3"); client = mockB }
            skills { skill<String, String>("sb", "Step B") { tools() } }
        }
        val c = agent<String, String>("c") {
            model { ollama("llama3"); client = mockC }
            skills { skill<String, String>("sc", "Step C") { tools() } }
        }

        val pipeline = a then b then c
        val result = pipeline("start")

        assertEquals("step-c", result)
        assertEquals(listOf("a", "b", "c"), log, "Agents must execute in pipeline order")
    }

    @Test
    fun `pipeline passes output of first agent as input to second`() {
        val captured = mutableListOf<String>()
        val mockA = ModelClient { _ -> LlmResponse.Text("intermediate-value") }
        val mockB = ModelClient { msgs ->
            val userMsg = msgs.last { it.role == "user" }.content
            captured.add(userMsg)
            LlmResponse.Text("final")
        }

        val first = agent<String, String>("first") {
            model { ollama("llama3"); client = mockA }
            skills { skill<String, String>("s1", "First step") { tools() } }
        }
        val second = agent<String, String>("second") {
            model { ollama("llama3"); client = mockB }
            skills { skill<String, String>("s2", "Second step") { tools() } }
        }

        val pipeline = first then second
        pipeline("start")

        assertEquals("intermediate-value", captured.single(),
            "Second agent should receive first agent's output as input")
    }

    @Test
    fun `pipeline with skill selection routes correctly at each stage`() {
        val chosen = mutableListOf<String>()

        // First agent: predicate routing
        val router = agent<String, String>("router") {
            skills {
                skill<String, String>("shout", "Make text loud") {
                    implementedBy { it.uppercase() }
                }
                skill<String, String>("whisper", "Make text quiet") {
                    implementedBy { it.lowercase() }
                }
            }
            skillSelection { input -> if (input.contains("loud")) "shout" else "whisper" }
            onSkillChosen { chosen.add(it) }
        }

        // Second agent: LLM-driven
        val mock = ModelClient { _ -> LlmResponse.Text("done") }
        val processor = agent<String, String>("processor") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("process", "Process text") { tools() } }
            onSkillChosen { chosen.add(it) }
        }

        val pipeline = router then processor
        pipeline("make it loud")

        assertEquals(listOf("shout", "process"), chosen)
    }

    @Test
    fun `pipeline with tool-calling agentic agent`() {
        val toolUses = mutableListOf<String>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(
            agents_engine.model.ToolCall(name = "reverse", arguments = mapOf("text" to "hello"))
        )))
        responses.add(LlmResponse.Text("olleh"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val reverser = agent<String, String>("reverser") {
            model { ollama("llama3"); client = mock }
            tools { tool("reverse", "Reverse a string") { args -> args["text"].toString().reversed() } }
            skills { skill<String, String>("rev", "Reverse text via tool") { tools("reverse") } }
            onToolUse { name, _, _ -> toolUses.add(name) }
        }
        val suffix = agent<String, String>("suffix") {
            skills { skill<String, String>("suffix", "Add suffix") {
                implementedBy { "$it!" }
            }}
        }

        val pipeline = reverser then suffix
        assertEquals("olleh!", pipeline("hello"))
        assertEquals(listOf("reverse"), toolUses)
    }

    // --- Integration tests with real LLM ---

    @Tag("live-llm")
    @Test
    fun `pipeline of two agentic agents with real LLM`() {
        val skills = mutableListOf<String>()

        val translator = agent<String, String>("translator") {
            prompt("You translate text to French. Reply with ONLY the translation, no explanation.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("translate", "Translate text to French") { tools() } }
            onSkillChosen { skills.add(it) }
        }

        val counter = agent<String, String>("counter") {
            prompt("You count the number of words in the given text. Reply with ONLY the number, nothing else.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("count", "Count words in text") { tools() } }
            onSkillChosen { skills.add(it) }
        }

        val pipeline = translator then counter
        val result = pipeline("Hello world")

        println("Translation → Count pipeline result: $result")
        assertEquals(listOf("translate", "count"), skills)
        // French translation of "Hello world" is typically 2-3 words, so count should be a small number
        val count = result.trim().toIntOrNull()
        assertTrue(count != null && count in 1..10,
            "Expected a small word count, got: $result")
    }

    @Tag("live-llm")
    @Test
    fun `three-stage pipeline extract then transform then format with real LLM`() {
        // Stage 1: extract keywords (LLM)
        val extractor = agent<String, String>("extractor") {
            prompt("Extract the key nouns from the text. Reply with a comma-separated list of nouns only, nothing else.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("extract", "Extract key nouns from text") { tools() } }
        }

        // Stage 2: uppercase (lambda — deterministic)
        val uppercaser = agent<String, String>("uppercaser") {
            skills { skill<String, String>("upper", "Uppercase the input") {
                implementedBy { it.uppercase() }
            }}
        }

        // Stage 3: format as bullet list (LLM)
        val formatter = agent<String, String>("formatter") {
            prompt("Format the comma-separated items as a markdown bullet list. Each item on its own line starting with '- '. Nothing else.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            skills { skill<String, String>("format", "Format as bullet list") { tools() } }
        }

        val pipeline = extractor then uppercaser then formatter
        val result = pipeline("The quick brown fox jumps over the lazy dog")

        println("Extract → Upper → Format result:\n$result")
        assertTrue(result.contains("- "), "Expected markdown bullet list, got: $result")
        // After uppercaser, all text should be uppercase in the bullets
        val bulletLines = result.lines().filter { it.trimStart().startsWith("- ") }
        assertTrue(bulletLines.isNotEmpty(), "Expected at least one bullet point")
        bulletLines.forEach { line ->
            val item = line.substringAfter("- ").trim()
            assertEquals(item.uppercase(), item,
                "Expected uppercase items after uppercaser stage, got: $item")
        }
    }
}
