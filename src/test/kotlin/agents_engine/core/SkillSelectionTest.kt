package agents_engine.core

import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillSelectionTest {

    // --- Predicate-based routing ---

    @Test
    fun `skillSelection predicate picks the named skill`() {
        val a = agent<String, String>("a") {
            skills {
                skill<String, String>("upper", "Uppercase text") {
                    implementedBy { it.uppercase() }
                }
                skill<String, String>("lower", "Lowercase text") {
                    implementedBy { it.lowercase() }
                }
            }
            skillSelection { input ->
                if (input.startsWith("UP:")) "upper" else "lower"
            }
        }

        assertEquals("UP:HELLO", a("UP:hello"))
        assertEquals("hello", a("HELLO"))
    }

    @Test
    fun `skillSelection fires onSkillChosen with correct name`() {
        val chosen = mutableListOf<String>()
        val a = agent<String, String>("a") {
            skills {
                skill<String, String>("upper", "Uppercase") {
                    implementedBy { it.uppercase() }
                }
                skill<String, String>("lower", "Lowercase") {
                    implementedBy { it.lowercase() }
                }
            }
            skillSelection { input ->
                if (input.startsWith("UP:")) "upper" else "lower"
            }
            onSkillChosen { name -> chosen.add(name) }
        }

        a("UP:hello")
        a("world")
        assertEquals(listOf("upper", "lower"), chosen)
    }

    @Test
    fun `skillSelection returning unknown name throws`() {
        val a = agent<String, String>("a") {
            skills {
                skill<String, String>("upper", "Uppercase") {
                    implementedBy { it.uppercase() }
                }
            }
            skillSelection { "nonexistent" }
        }

        assertThrows<IllegalStateException> { a("hello") }
    }

    @Test
    fun `single-skill agent without skillSelection still works`() {
        val a = agent<String, String>("a") {
            skills {
                skill<String, String>("only", "The only skill") {
                    implementedBy { "processed: $it" }
                }
            }
        }

        assertEquals("processed: hello", a("hello"))
    }

    // --- LLM-based routing ---

    @Test
    fun `LLM routing picks skill by description`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.Text("upper"))       // routing turn
        responses.add(LlmResponse.Text("HELLO WORLD")) // agentic turn
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills {
                skill<String, String>("upper", "Convert text to uppercase") { tools() }
                skill<String, String>("lower", "Convert text to lowercase") { tools() }
            }
        }

        assertEquals("HELLO WORLD", a("Make this uppercase: hello world"))
    }

    @Test
    fun `LLM routing fires onSkillChosen with LLM-selected name`() {
        val chosen = mutableListOf<String>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.Text("lower"))  // routing turn
        responses.add(LlmResponse.Text("hello"))  // agentic turn
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills {
                skill<String, String>("upper", "Convert text to uppercase") { tools() }
                skill<String, String>("lower", "Convert text to lowercase") { tools() }
            }
            onSkillChosen { name -> chosen.add(name) }
        }

        a("lowercase this")
        assertEquals(listOf("lower"), chosen)
    }

    @Test
    fun `LLM routing prompt contains all candidate descriptions`() {
        val allMessages = mutableListOf<List<LlmMessage>>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.Text("upper"))  // routing turn
        responses.add(LlmResponse.Text("DONE"))   // agentic turn
        val mock = ModelClient { msgs -> allMessages.add(msgs.toList()); responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills {
                skill<String, String>("upper", "Convert text to uppercase") { tools() }
                skill<String, String>("lower", "Convert text to lowercase") { tools() }
            }
        }

        a("hello")

        // First call is the routing turn
        val routingMessages = allMessages[0]
        val systemPrompt = routingMessages.first { it.role == "system" }.content
        assertTrue(systemPrompt.contains("upper"), "Routing prompt should contain 'upper' skill name")
        assertTrue(systemPrompt.contains("Convert text to uppercase"), "Routing prompt should contain upper description")
        assertTrue(systemPrompt.contains("lower"), "Routing prompt should contain 'lower' skill name")
        assertTrue(systemPrompt.contains("Convert text to lowercase"), "Routing prompt should contain lower description")
    }

    @Test
    fun `LLM routing skipped when only one type-compatible skill`() {
        var callCount = 0
        val mock = ModelClient { _ -> callCount++; LlmResponse.Text("done") }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills {
                skill<String, String>("only-skill", "desc") { tools() }
            }
        }

        a("input")
        assertEquals(1, callCount, "Should call LLM only once (agentic loop), no routing turn")
    }

    @Test
    fun `LLM routing skipped when skillSelection predicate is set`() {
        var callCount = 0
        val mock = ModelClient { _ -> callCount++; LlmResponse.Text("done") }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills {
                skill<String, String>("upper", "uppercase") { tools() }
                skill<String, String>("lower", "lowercase") { tools() }
            }
            skillSelection { "upper" }
        }

        a("input")
        assertEquals(1, callCount, "Predicate takes priority — LLM routing should be skipped")
    }

    // --- Edge cases ---

    @Test
    fun `LLM routing throws when LLM returns unknown skill name`() {
        val mock = ModelClient { _ -> LlmResponse.Text("nonexistent") }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills {
                skill<String, String>("upper", "uppercase") { tools() }
                skill<String, String>("lower", "lowercase") { tools() }
            }
        }

        assertThrows<IllegalStateException> { a("input") }
    }

    @Test
    fun `no model with multiple same-type skills uses first-match fallback`() {
        val a = agent<String, String>("a") {
            skills {
                skill<String, String>("first", "first skill") {
                    implementedBy { "first: $it" }
                }
                skill<String, String>("second", "second skill") {
                    implementedBy { "second: $it" }
                }
            }
        }

        assertEquals("first: hello", a("hello"))
    }

    // --- Integration test with real LLM ---

    @Test
    fun `LLM routing picks correct skill with real model`() {
        val chosen = mutableListOf<String>()

        val a = agent<String, String>("router") {
            prompt("You are a helpful assistant. Perform the requested task.")
            model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }
            skills {
                skill<String, String>("summarize", "Summarize the given text into a brief summary") { tools() }
                skill<String, String>("translate-to-french", "Translate the given text to French") { tools() }
            }
            onSkillChosen { name -> chosen.add(name) }
        }

        val result = a("Translate this to French: Hello world")
        println("LLM routing result: $result")
        assertEquals("translate-to-french", chosen.single())
        assertTrue(
            result.lowercase().let { it.contains("bonjour") || it.contains("monde") || it.contains("salut") },
            "Expected French translation, got: $result"
        )
    }
}
