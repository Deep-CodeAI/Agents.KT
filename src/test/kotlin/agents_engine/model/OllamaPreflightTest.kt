package agents_engine.model

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.jupiter.api.assertThrows
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

// Tests for #1132 — OllamaPreflight verifies the Ollama HTTP endpoint is
// reachable BEFORE the REPL prints its banner / accepts user input. On
// failure: throw LlmProviderException with host:port + reason.
class OllamaPreflightTest {

    private val toStop = mutableListOf<() -> Unit>()

    @AfterTest fun cleanup() { toStop.forEach { runCatching { it() } } }

    private fun startApiTagsStub(status: Int = 200, body: String = """{"models":[]}"""): Int {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/tags") { ex ->
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.executor = null
        server.start()
        toStop.add { server.stop(0) }
        return server.address.port
    }

    @Test
    fun `succeeds when Ollama responds 200 to api tags`() {
        val port = startApiTagsStub()
        OllamaPreflight(host = "127.0.0.1", port = port).check()
        // No throw = success.
    }

    @Test
    fun `throws with host port when connection is refused`() {
        // Bind a server socket just to find a free port, then close it so
        // the port is unbound while the preflight runs.
        val freePort = ServerSocket(0).use { it.localPort }

        val ex = assertThrows<LlmProviderException> {
            OllamaPreflight(
                host = "127.0.0.1",
                port = freePort,
                connectTimeout = 500.milliseconds,
            ).check()
        }
        assertTrue(
            ex.message!!.contains("127.0.0.1:$freePort"),
            "error must name host:port; got: ${ex.message}",
        )
    }

    @Test
    fun `throws when server returns non-2xx`() {
        val port = startApiTagsStub(status = 500, body = "boom")
        val ex = assertThrows<LlmProviderException> {
            OllamaPreflight(host = "127.0.0.1", port = port).check()
        }
        assertTrue(
            ex.message!!.contains("500") || ex.message!!.contains("status", ignoreCase = true),
            "error should mention non-2xx status; got: ${ex.message}",
        )
    }
}
