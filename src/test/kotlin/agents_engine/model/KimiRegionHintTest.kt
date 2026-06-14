package agents_engine.model

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #4511 — a valid Moonshot key fails with "Invalid Authentication" when the client
// points at the wrong region (api.moonshot.cn vs api.moonshot.ai). The auth error
// must POINT DEVS to the region mismatch, not just echo "Invalid Authentication".

class KimiRegionHintTest {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/chat/completions") { ex ->
            val body = """{"error":{"type":"invalid_authentication_error","message":"Invalid Authentication"}}"""
                .toByteArray()
            ex.sendResponseHeaders(401, body.size.toLong())
            ex.responseBody.use { it.write(body) }
            ex.close()
        }
        executor = null
        start()
    }
    private val baseUrl get() = "http://localhost:${server.address.port}"

    @AfterTest fun stop() = server.stop(0)

    @Test
    fun `an auth error points the dev at the moonshot region mismatch`() {
        val client = KimiClient(apiKey = "sk-intlkey", model = "moonshot-v1-8k", baseUrl = baseUrl)
        val ex = assertFailsWith<LlmProviderException> { client.chat(listOf(LlmMessage("user", "hi"))) }
        val msg = ex.message.orEmpty()
        assertTrue("api.moonshot.ai" in msg, "names the international endpoint: $msg")
        assertTrue(
            "INTERNATIONAL_BASE_URL" in msg || "platform.moonshot" in msg,
            "tells the dev how to switch region: $msg",
        )
    }

    @Test
    fun `the region constants are distinct`() {
        assertTrue(KimiClient.CHINA_BASE_URL.endsWith(".cn"))
        assertTrue(KimiClient.INTERNATIONAL_BASE_URL.endsWith(".ai"))
    }
}
