package agents_engine.model

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// Tests for #853 — OllamaClient enforces a hard cap on inbound response body size.
// A malicious or buggy upstream that streams unbounded bytes must not OOM the JVM.
class OllamaClientResponseSizeLimitTest {

    private val toStop = mutableListOf<() -> Unit>()

    @AfterTest fun cleanup() { toStop.forEach { runCatching { it() } } }

    /** Starts a stub server that returns the given bytes for any /api/chat POST. */
    private fun startStub(responseBytes: ByteArray): Int {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/chat") { ex ->
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, responseBytes.size.toLong())
            ex.responseBody.use { it.write(responseBytes) }
        }
        server.executor = null
        server.start()
        toStop.add { server.stop(0) }
        return server.address.port
    }

    @Test
    fun `response above maxResponseBytes throws LlmProviderException`() {
        // Stub returns 5 KiB; client cap is 1 KiB.
        val port = startStub(ByteArray(5 * 1024) { 'x'.code.toByte() })
        val client = OllamaClient(
            host = "127.0.0.1",
            port = port,
            model = "fake",
            maxResponseBytes = 1024,
        )
        try {
            client.chat(listOf(LlmMessage("user", "hi")))
            fail("expected LlmProviderException for over-cap response")
        } catch (e: LlmProviderException) {
            assertTrue(
                e.message!!.contains("exceeded", ignoreCase = true) ||
                    e.message!!.contains("OOM", ignoreCase = true),
                "expected size-limit message, got: ${e.message}",
            )
        }
    }

    @Test
    fun `response at the cap is processed normally`() {
        val body = """{"message":{"content":"ok"}}""".toByteArray(Charsets.UTF_8)
        val port = startStub(body)
        val client = OllamaClient(
            host = "127.0.0.1",
            port = port,
            model = "fake",
            maxResponseBytes = 4096,
        )
        val response = client.chat(listOf(LlmMessage("user", "hi")))
        assertTrue(response is LlmResponse.Text, "expected text response, got: $response")
        assertEquals("ok", (response as LlmResponse.Text).content)
    }
}
