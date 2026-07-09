package agents_engine.model

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #4882 — the streaming path must surface a non-2xx HTTP response as LlmProviderException,
// matching the non-streaming contract (sendChat -> HttpModelClientSupport.sendBounded throws on
// 4xx/5xx). Previously `sendChatStream` returned `response.body()` unchecked, so an error body
// (no `data:` lines) was swallowed by parseSseStream into a silent, empty, success-looking stream
// (a lone End) — no exception, no observability. Confirmed against a real 401 from api.moonshot.cn.
class OpenAiClientStreamErrorTest {

    private var server: HttpServer? = null

    private fun serverReturning(status: Int, body: String): String {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { ex ->
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
                ex.close()
            }
            executor = null
            start()
        }
        server = srv
        return "http://localhost:${srv.address.port}"
    }

    @AfterTest fun stop() {
        server?.stop(0)
    }

    @Test
    fun `streaming surfaces a 401 as LlmProviderException with status and body`() = runBlocking {
        val baseUrl = serverReturning(401, """{"error":{"message":"Invalid Authentication"}}""")
        val client = OpenAiClient(apiKey = "sk-bad", model = "gpt-4o-mini", baseUrl = baseUrl)

        val ex = assertFailsWith<LlmProviderException> {
            client.chatStream(listOf(LlmMessage("user", "hi"))).toList()
        }
        val msg = ex.message.orEmpty()
        assertTrue("401" in msg, "message must name the HTTP status: $msg")
        assertTrue("Invalid Authentication" in msg, "message must include the provider error body: $msg")
        assertTrue("OpenAI" in msg, "message must name the provider label: $msg")
    }

    @Test
    fun `streaming surfaces a 5xx as LlmProviderException`() = runBlocking {
        val baseUrl = serverReturning(503, """{"error":{"message":"upstream unavailable"}}""")
        val client = OpenAiClient(apiKey = "sk", model = "gpt-4o-mini", baseUrl = baseUrl)

        val ex = assertFailsWith<LlmProviderException> {
            client.chatStream(listOf(LlmMessage("user", "hi"))).toList()
        }
        assertTrue("503" in ex.message.orEmpty(), "message must name the 5xx status: ${ex.message}")
    }
}
