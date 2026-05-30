package agents_engine.testing

import agents_engine.core.agent
import agents_engine.model.LlmResponse
import agents_engine.model.Tool
import agents_engine.model.ToolCall
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #2492 — DeterministicModelClient. Pins:
 *
 * 1. Scripted responses returned in order — agent loop is byte-identical
 *    across runs against the same script.
 * 2. The client records every request the agent built up — useful for
 *    asserting on conversation shape.
 * 3. Exhaustion throws a clear error naming the call index.
 * 4. `remaining()` lets a test pin "agent consumed exactly N turns."
 */
class DeterministicModelClientTest {

    @Test
    fun `scripted text response is returned to the agent`() {
        val mock = DeterministicModelClient(LlmResponse.Text("hello back"))
        val a = agent<String, String>("a") {
            model { ollama("t"); client = mock }
            skills { skill<String, String>("s", "") { implementedBy { "fallback" } } }
        }
        // The implementedBy skill is non-agentic — won't call the model.
        // Switch to a tools-driven skill to exercise the mock.
        val b = agent<String, String>("b") {
            model { ollama("t"); client = mock }
            skills { skill<String, String>("s", "") { tools() } }
        }
        assertEquals("hello back", b("any"))
    }

    @Test
    fun `multi-turn tool round trip plays out scripted responses in order`() {
        val mock = DeterministicModelClient(
            LlmResponse.ToolCalls(listOf(ToolCall("lookup", mapOf("id" to "42")))),
            LlmResponse.Text("found 42"),
        )
        val a = agent<String, String>("two-turn") {
            lateinit var lookup: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools { lookup = tool("lookup", "lookup by id") { args -> "value-${args["id"]}" } }
            skills { skill<String, String>("s", "") { tools(lookup) } }
        }
        assertEquals("found 42", a("go"))
        assertEquals(0, mock.remaining(), "both scripted responses consumed")
    }

    @Test
    fun `requests records each chat call's message list`() {
        val mock = DeterministicModelClient(LlmResponse.Text("done"))
        val a = agent<String, String>("recorder") {
            model { ollama("t"); client = mock }
            skills { skill<String, String>("s", "") { tools() } }
        }
        a("hello world")
        assertEquals(1, mock.requests.size)
        val firstCallMessages = mock.requests.first()
        // The agent's loop sends system + user at minimum.
        assertTrue(firstCallMessages.any { it.role == "user" && it.content == "hello world" })
    }

    @Test
    fun `exhausted script throws DeterministicScriptExhausted with call index`() {
        // Only one response, but the agent needs two turns (tool call → text).
        val mock = DeterministicModelClient(
            LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap()))),
        )
        val a = agent<String, String>("exhausting") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools { step = tool("step", "step once") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
        }
        val ex = assertThrows<DeterministicScriptExhausted> { a("go") }
        assertEquals(1, ex.callIndex, "first scripted response consumed; second call exhausts")
        assertEquals(1, ex.scriptSize)
    }

    @Test
    fun `remaining reports unconsumed scripted responses`() {
        val mock = DeterministicModelClient(
            LlmResponse.Text("first"),
            LlmResponse.Text("second"),
            LlmResponse.Text("third"),
        )
        assertEquals(3, mock.remaining())
    }

    @Test
    fun `two runs against the same script produce byte-identical output (byte-determinism AC)`() {
        // The acceptance criterion: same scripted client + same agent + same input → same output.
        fun buildAgent(mock: DeterministicModelClient) = agent<String, String>("repro") {
            lateinit var step: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools { step = tool("step", "") { _ -> "ok" } }
            skills { skill<String, String>("s", "") { tools(step) } }
        }

        val script = listOf(
            LlmResponse.ToolCalls(listOf(ToolCall("step", emptyMap()))),
            LlmResponse.Text("the same output"),
        )
        val outA = buildAgent(DeterministicModelClient(script)).invoke("input")
        val outB = buildAgent(DeterministicModelClient(script)).invoke("input")
        assertEquals(outA, outB, "byte-identical output across runs")
    }
}
