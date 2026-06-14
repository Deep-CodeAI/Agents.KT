package agents_engine.speechserver

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #4508 — ADVERSARIAL: a hostile client must not be able to (a) exhaust memory with
// a giant body, or (b) get the unauthenticated server bound to all interfaces.
// RED before the fix; GREEN after.

class SpeechServerSecurityTest {

    private val stt = ServerSttBackend { _, _ -> "ok" }
    private val tts = ServerTtsBackend { _, _, _ -> ByteArray(0) }
    private val http = HttpClient.newHttpClient()
    private var server: SpeechServer? = null

    @AfterTest fun stop() = server?.stop(0) ?: Unit

    private fun post(path: String, body: ByteArray, contentType: String): HttpResponse<String> {
        val s = server!!
        val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${s.port}$path"))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `an over-cap request body is rejected with 413, not read unbounded`() {
        server = SpeechServer(stt, tts, port = 0, maxRequestBytes = 1024).start()
        val oversized = ByteArray(64 * 1024) { 'a'.code.toByte() } // 64 KB, far over the 1 KB cap
        val resp = post("/v1/audio/speech", oversized, "application/json")
        assertEquals(413, resp.statusCode(), "HARM: server read an over-cap body instead of rejecting it")
    }

    @Test
    fun `a within-cap request is processed normally`() {
        server = SpeechServer(stt, tts, port = 0, maxRequestBytes = 1024).start()
        val body = """{"input":"hi","response_format":"wav"}""".toByteArray()
        val resp = post("/v1/audio/speech", body, "application/json")
        assertEquals(200, resp.statusCode())
    }

    @Test
    fun `binding a non-loopback host is refused without explicit opt-in`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            SpeechServer(stt, tts, port = 0, host = "0.0.0.0")
        }
        assertTrue("loopback" in ex.message.orEmpty(), "message explains the exposure: ${ex.message}")
    }

    @Test
    fun `loopback host is allowed and binds normally`() {
        server = SpeechServer(stt, tts, port = 0, host = "127.0.0.1").start()
        assertTrue(server!!.port > 0)
    }

    @Test
    fun `isLoopbackHost classifies hosts`() {
        assertTrue(isLoopbackHost("127.0.0.1"))
        assertTrue(isLoopbackHost("localhost"))
        assertTrue(isLoopbackHost("::1"))
        assertTrue(!isLoopbackHost("0.0.0.0"), "the all-interfaces wildcard is NOT loopback")
    }
}
