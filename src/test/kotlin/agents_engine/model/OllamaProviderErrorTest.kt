package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for #702 — Ollama returns top-level error envelopes (e.g. capability
 * errors like `{"error":"... does not support tools"}`) when the request is
 * malformed or the model can't satisfy it. Previously these were silently
 * passed through as `LlmResponse.Text(rawJson)` and confused downstream
 * `transformOutput` parsers. Now they throw `LlmProviderException` so the
 * caller sees a clean provider-boundary error.
 */
class OllamaProviderErrorTest {

    private fun newClient(): OllamaClient =
        OllamaClient(host = "localhost", port = 11434, model = "test-model")

    @Test
    fun `parseResponse throws LlmProviderException on top-level error field`() {
        val errorBody = """{"error":"registry.ollama.ai/library/gemma3:4b does not support tools"}"""
        try {
            newClient().parseResponse(errorBody)
            fail("expected LlmProviderException")
        } catch (e: LlmProviderException) {
            assertTrue(e.message!!.contains("does not support tools"),
                "exception must surface the provider's error verbatim: ${e.message}")
            assertTrue(e.message!!.contains("Ollama", ignoreCase = true),
                "exception must identify the provider: ${e.message}")
        }
    }

    @Test
    fun `parseResponse on normal response with message content returns Text (regression)`() {
        val okBody = """{"message":{"role":"assistant","content":"hello"}}"""
        val response = newClient().parseResponse(okBody)
        assertTrue(response is LlmResponse.Text)
        assertEquals("hello", response.content)
    }

    @Test
    fun `parseResponse on response without message and without error falls back to Text (regression)`() {
        // Defensive: keep old behavior for malformed-but-not-"error"-shaped bodies.
        val weirdBody = """{"unknown":"shape"}"""
        val response = newClient().parseResponse(weirdBody)
        assertTrue(response is LlmResponse.Text, "got ${response::class.simpleName}")
    }

    @Test
    fun `agent invocation propagates LlmProviderException without invoking transformOutput`() {
        // Reproduces the user-facing failure mode: a ModelClient that surfaces
        // a provider-error throw must not let the user's transformOutput see
        // the error JSON as if it were model output.
        var transformOutputCalled = false
        val mock = ModelClient { _ ->
            throw LlmProviderException("Ollama returned an error: model does not support tools")
        }

        val a = agent<String, String>("a") {
            model { ollama("gemma3:4b"); client = mock }
            skills {
                skill<String, String>("s", "stub") {
                    tools()
                    transformOutput { text ->
                        transformOutputCalled = true
                        text
                    }
                }
            }
        }

        try {
            a("input")
            fail("expected LlmProviderException to propagate")
        } catch (e: LlmProviderException) {
            assertTrue(e.message!!.contains("does not support tools"))
        }

        assertEquals(false, transformOutputCalled,
            "transformOutput must NOT be called when provider rejects the request")
    }
}
