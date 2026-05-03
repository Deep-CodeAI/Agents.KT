package agents_engine.model

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpTimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

// Tests for #852 — OllamaClient enforces a per-request HTTP timeout.
// A non-responsive endpoint must NOT block the caller indefinitely.
class OllamaClientTimeoutTest {

    private val toStop = mutableListOf<() -> Unit>()

    @AfterTest fun cleanup() { toStop.forEach { runCatching { it() } } }

    /** Starts a stub HTTP server that accepts the connection and never replies. */
    private fun startBlackHole(): Int {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/chat") { /* never write a response — exchange leaks */ }
        server.executor = null
        server.start()
        toStop.add { server.stop(0) }
        return server.address.port
    }

    @Test
    fun `request that exceeds requestTimeout throws HttpTimeoutException`() {
        val port = startBlackHole()
        val client = OllamaClient(
            host = "127.0.0.1",
            port = port,
            model = "fake",
            requestTimeout = 250.milliseconds,
        )

        val started = System.nanoTime()
        try {
            client.chat(listOf(LlmMessage("user", "hi")))
            fail("expected HttpTimeoutException; chat returned normally")
        } catch (e: HttpTimeoutException) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(
                elapsedMs in 200..2_000,
                "timeout fired but elapsed=${elapsedMs}ms, expected ~250ms (sanity bound)",
            )
        } catch (e: Throwable) {
            fail("expected HttpTimeoutException, got ${e::class.simpleName}: ${e.message}")
        }
    }
}
