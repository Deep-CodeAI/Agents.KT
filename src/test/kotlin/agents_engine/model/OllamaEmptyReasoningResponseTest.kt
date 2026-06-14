package agents_engine.model

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #4512 — gpt-oss(:cloud) via Ollama generates tokens but surfaces empty `content`
// (no tool call, no thinking). agents.kt used to return LlmResponse.Text("") and the
// agentic loop silently failed. It must instead fail with an actionable message.

class OllamaEmptyReasoningResponseTest {

    private val toStop = mutableListOf<() -> Unit>()

    @AfterTest fun stop() = toStop.forEach { it() }

    private fun startStub(body: String): Int {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/chat") { ex ->
            val b = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.executor = null
        server.start()
        toStop.add { server.stop(0) }
        return server.address.port
    }

    private fun client(port: Int, model: String = "gpt-oss:20b") =
        OllamaClient(host = "127.0.0.1", port = port, model = model)

    @Test
    fun `tokens generated but nothing surfaced yields an actionable error`() {
        val port = startStub(
            """{"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop",""" +
                """"prompt_eval_count":74,"eval_count":26}""",
        )
        val ex = assertFailsWith<LlmProviderException> { client(port).chat(listOf(LlmMessage("user", "hi"))) }
        val m = ex.message.orEmpty()
        assertTrue("gpt-oss:20b" in m, "names the model: $m")
        assertTrue("no content" in m.lowercase(), m)
        assertTrue(
            "llama" in m.lowercase() || "qwen" in m.lowercase() || "tool-calling" in m.lowercase(),
            "suggests a working model: $m",
        )
    }

    @Test
    fun `an empty response with no tokens generated is NOT flagged (no false positive)`() {
        // content="" but no eval_count → the model produced nothing at all; plain Text, no throw.
        val port = startStub("""{"message":{"role":"assistant","content":""},"done":true}""")
        val resp = client(port, model = "m").chat(listOf(LlmMessage("user", "hi")))
        assertTrue(resp is LlmResponse.Text && resp.content.isEmpty())
    }

    @Test
    fun `a normal text response is unaffected`() {
        val port = startStub("""{"message":{"role":"assistant","content":"hello"},"done":true,"eval_count":5}""")
        val resp = client(port, model = "m").chat(listOf(LlmMessage("user", "hi")))
        assertTrue(resp is LlmResponse.Text && resp.content == "hello")
    }
}
