package agents_engine.core

import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Demonstrates the canonical loadResource pattern end-to-end with the
// Fibonacci memory agent (#980). Runs without a live LLM — the model is a
// scripted ModelClient that produces a tool-call-then-text exchange. The
// test asserts:
//   - the resource-loaded prompt actually reaches the LLM as a system message
//   - the system message body matches the prompt fixture verbatim
//   - the agent invocation produces the expected output
//
// This is the inverse of FibonacciMemoryTest (which is `live-llm` tagged
// and only runs against a real Ollama). Together they verify the same
// agent setup works against both a real LLM and a deterministic mock.
class FibonacciResourcePromptTest {

    @Test
    fun `Fibonacci agent built with loadResource — system message contains the loaded prompt`() {
        val bank = MemoryBank()
        val capturedSystem = mutableListOf<String>()

        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(
            ToolCall("memory_read", emptyMap()),
        )))
        responses.add(LlmResponse.ToolCalls(listOf(
            ToolCall("memory_write", mapOf("content" to "0|1")),
        )))
        responses.add(LlmResponse.Text("1"))

        val mock = ModelClient { messages ->
            // Capture the FIRST system message body on every chat call so the
            // test can verify the resource-loaded prompt actually got there.
            messages.firstOrNull { it.role == "system" }?.let { capturedSystem += it.content }
            responses.removeFirst()
        }

        val fib = agent<String, Int>("fibonacci") {
            prompt(loadResource("prompts/fibonacci.md"))
            memory(bank)
            model { ollama("test"); client = mock }
            skills {
                skill<String, Int>("fib", "Generate next Fibonacci number") {
                    tools()
                    transformOutput {
                        it.trim().toIntOrNull()
                            ?: Regex("\\d+").find(it)?.value?.toInt()
                            ?: error("No int in: $it")
                    }
                }
            }
        }

        val result = fib("do it")

        assertEquals(1, result, "first Fibonacci number must be 1")
        // The resource content (loaded once at agent construction) reached the
        // LLM as part of the system message.
        val firstSystem = capturedSystem.firstOrNull().orEmpty()
        assertTrue(
            firstSystem.contains("You maintain a Fibonacci sequence in memory."),
            "system message should include the resource-loaded prompt header. Got: $firstSystem",
        )
        assertTrue(
            firstSystem.contains("memory=\"21|34\" → 21+34=55"),
            "worked-example block from the resource must reach the system message",
        )
        // Memory bank was actually written through the agent — round-trip works.
        assertEquals("0|1", bank.read("fibonacci"))
    }

    @Test
    fun `the Fibonacci prompt resource is non-trivial (sanity check on the fixture)`() {
        // Guard against an empty/half-deleted fixture silently breaking the
        // live-LLM FibonacciMemoryTest. If someone accidentally truncates
        // the resource, this fast-running unit test fails first.
        val prompt = loadResource("prompts/fibonacci.md")
        assertTrue(prompt.length > 200, "Fibonacci prompt looks too short: ${prompt.length} chars")
        assertTrue(prompt.contains("memory_read"), "prompt should mention memory_read")
        assertTrue(prompt.contains("memory_write"), "prompt should mention memory_write")
        assertTrue(prompt.contains("PROCEDURE"), "prompt should include the PROCEDURE section")
    }
}
