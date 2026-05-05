package agents_engine.core

import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.ToolCall
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Tests for #965 — Agent.observe { } emits a sealed PipelineEvent for each
// of the four bridged hooks (skill chosen, tool called, knowledge loaded,
// error). Composes additively with any prior listeners.
class ObservePipelineEventTest {

    @Test
    fun `SkillChosen event fires when the agent picks a skill`() {
        val mock = ModelClient { _ -> LlmResponse.Text("done") }

        val events = mutableListOf<PipelineEvent>()
        val a = agent<String, String>("alice") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("greet", "Greet") { tools() } }
        }
        a.observe { events += it }

        a("hi")

        val skillEvent = events.filterIsInstance<PipelineEvent.SkillChosen>().single()
        assertEquals("alice", skillEvent.agentName)
        assertEquals("greet", skillEvent.skillName)
        assertNotNull(skillEvent.timestamp)
    }

    @Test
    fun `ToolCalled event fires for each tool invocation`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(
            ToolCall(name = "echo", arguments = mapOf("text" to "hello")),
        )))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val events = mutableListOf<PipelineEvent>()
        val a = agent<String, String>("a") {
            lateinit var echo: agents_engine.model.Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            tools { echo = tool("echo", "") { args -> args["text"].toString().uppercase() } }
            skills { skill<String, String>("s", "s") { tools(echo) } }
        }
        a.observe { events += it }

        a("input")

        val toolEvent = events.filterIsInstance<PipelineEvent.ToolCalled>().single()
        assertEquals("a", toolEvent.agentName)
        assertEquals("echo", toolEvent.toolName)
        assertEquals("hello", toolEvent.arguments["text"])
        assertEquals("HELLO", toolEvent.result)
    }

    @Test
    fun `KnowledgeLoaded event fires when LLM fetches a knowledge entry`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(
            ToolCall(name = "style-guide", arguments = emptyMap()),
        )))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val events = mutableListOf<PipelineEvent>()
        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills {
                skill<String, String>("s", "s") {
                    tools()
                    knowledge("style-guide", "Coding style") { "Prefer val over var." }
                }
            }
        }
        a.observe { events += it }

        a("task")

        val k = events.filterIsInstance<PipelineEvent.KnowledgeLoaded>().single()
        assertEquals("a", k.agentName)
        assertEquals("style-guide", k.entryName)
        assertEquals("Prefer val over var.".length, k.contentLength)
    }

    @Test
    fun `ErrorOccurred event fires when invocation throws`() {
        val mock = ModelClient { _ -> throw RuntimeException("transport down") }

        val events = mutableListOf<PipelineEvent>()
        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("s", "s") { tools() } }
        }
        a.observe { events += it }

        assertThrows<RuntimeException> { a("input") }

        val err = events.filterIsInstance<PipelineEvent.ErrorOccurred>().single()
        assertEquals("a", err.agentName)
        assertEquals("transport down", err.error.message)
    }

    @Test
    fun `observe composes with a prior onSkillChosen — both fire`() {
        val mock = ModelClient { _ -> LlmResponse.Text("done") }

        val direct = mutableListOf<String>()
        val observed = mutableListOf<PipelineEvent>()

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("s", "s") { tools() } }
            onSkillChosen { name -> direct += name }
        }
        a.observe { observed += it }

        a("input")

        assertEquals(listOf("s"), direct)
        assertEquals(1, observed.filterIsInstance<PipelineEvent.SkillChosen>().size)
    }

    @Test
    fun `two observe registrations both receive events`() {
        val mock = ModelClient { _ -> LlmResponse.Text("done") }

        val first = mutableListOf<PipelineEvent>()
        val second = mutableListOf<PipelineEvent>()

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("s", "s") { tools() } }
        }
        a.observe { first += it }
        a.observe { second += it }

        a("input")

        // Both observers must see the SkillChosen event.
        assertEquals(1, first.filterIsInstance<PipelineEvent.SkillChosen>().size)
        assertEquals(1, second.filterIsInstance<PipelineEvent.SkillChosen>().size)
    }

    @Test
    fun `event timestamps are non-decreasing across a multi-event invocation`() {
        // Sanity: a tool call followed by a final text should produce events
        // in monotonic order. Useful for downstream consumers that sort by
        // timestamp.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "noop", arguments = emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val events = mutableListOf<PipelineEvent>()
        val a = agent<String, String>("a") {
            lateinit var noop: agents_engine.model.Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            tools { noop = tool("noop", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools(noop) } }
        }
        a.observe { events += it }

        a("input")

        val timestamps = events.map { it.timestamp }
        assertTrue(timestamps.size >= 2)
        for (i in 1 until timestamps.size) {
            assertTrue(
                !timestamps[i].isBefore(timestamps[i - 1]),
                "event ${i} timestamp ${timestamps[i]} is before ${timestamps[i - 1]}",
            )
        }
    }
}
