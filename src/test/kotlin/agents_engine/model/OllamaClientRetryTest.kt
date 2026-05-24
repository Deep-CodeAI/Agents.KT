package agents_engine.model

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * #2381 — OllamaClient retry policy for transient upstream failures.
 *
 * Ollama Cloud (and self-hosted Ollama under load) periodically returns
 * structured `{"error":"..."}` envelopes describing transport-level
 * conditions: `"unexpected EOF"` from the edge layer, `"Internal Server
 * Error"`, `"Service Unavailable"`, etc. These are retriable.
 *
 * Hard errors — model-not-found, capability mismatch, malformed
 * request, auth — are not retriable and must fail fast.
 */
class OllamaClientRetryTest {

    @Test
    fun `transient unexpected EOF retries and eventually succeeds`() {
        val attempts = AtomicInteger(0)
        val client = object : OllamaClient(model = "test") {
            override fun sendChat(body: String): String {
                val n = attempts.incrementAndGet()
                return if (n < 3) {
                    """{"error":"Post \"https://ollama.com:443/api/chat?ts=123\": unexpected EOF"}"""
                } else {
                    """{"message":{"role":"assistant","content":"ok after retry"}}"""
                }
            }
        }
        val response = client.chat(listOf(LlmMessage("user", "hi")))
        assertIs<LlmResponse.Text>(response)
        assertEquals("ok after retry", response.content)
        assertEquals(3, attempts.get(), "expected initial + 2 retries before success")
    }

    @Test
    fun `transient internal server error retries`() {
        val attempts = AtomicInteger(0)
        val client = object : OllamaClient(model = "test") {
            override fun sendChat(body: String): String {
                val n = attempts.incrementAndGet()
                return if (n < 2) {
                    """{"error":"Internal Server Error (ref: abc-123)"}"""
                } else {
                    """{"message":{"role":"assistant","content":"after 500"}}"""
                }
            }
        }
        val response = client.chat(listOf(LlmMessage("user", "hi")))
        assertEquals("after 500", (response as LlmResponse.Text).content)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `non-transient model-not-found fails fast without retry`() {
        val attempts = AtomicInteger(0)
        val client = object : OllamaClient(model = "test") {
            override fun sendChat(body: String): String {
                attempts.incrementAndGet()
                return """{"error":"model 'imaginary-model' not found, try pulling it first"}"""
            }
        }
        val ex = assertFails { client.chat(listOf(LlmMessage("user", "hi"))) }
        assertIs<LlmProviderException>(ex)
        assertTrue("not found" in (ex.message ?: ""), "expected model-not-found message, got: ${ex.message}")
        assertEquals(1, attempts.get(), "non-transient errors must NOT retry — caller needs the error now")
    }

    @Test
    fun `non-transient capability mismatch fails fast without retry`() {
        val attempts = AtomicInteger(0)
        val client = object : OllamaClient(model = "test") {
            override fun sendChat(body: String): String {
                attempts.incrementAndGet()
                return """{"error":"model 'plain-llama' does not support tools"}"""
            }
        }
        // The capability message has its own remediation path in OllamaClient
        // (inline-prompt fallback); regardless, it should not retry.
        runCatching { client.chat(listOf(LlmMessage("user", "hi"))) }
        // Capability-mismatch triggers the inline-prompt retry inside chat(),
        // which performs ONE extra send. Two total sends maximum — no
        // exponential transient-retry loop on top.
        assertTrue(attempts.get() <= 2, "capability mismatch took ${attempts.get()} attempts — must not enter transient-retry loop")
    }

    @Test
    fun `persistent transient error exhausts retries and throws`() {
        val attempts = AtomicInteger(0)
        val client = object : OllamaClient(model = "test") {
            override fun sendChat(body: String): String {
                attempts.incrementAndGet()
                return """{"error":"unexpected EOF"}"""
            }
        }
        val ex = assertFails { client.chat(listOf(LlmMessage("user", "hi"))) }
        assertIs<LlmProviderException>(ex)
        assertTrue("EOF" in (ex.message ?: ""))
        assertEquals(3, attempts.get(), "expected exactly maxAttempts=3 tries before giving up")
    }
}
