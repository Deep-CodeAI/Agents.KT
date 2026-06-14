package agents_engine.speechserver

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #4509 — hardening: requests are handled concurrently (a slow one can't stall the
// others), and a backend failure doesn't leak its exception text to the client.

class SpeechServerHardeningTest {

    private val http = HttpClient.newHttpClient()
    private var server: SpeechServer? = null

    @AfterTest fun stop() = server?.stop(0) ?: Unit

    private fun post(path: String, body: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server!!.port}$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `two requests are handled concurrently, not serialized`() {
        val inFlight = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val both = CountDownLatch(2)
        // Each TTS call records concurrency, then waits until BOTH are in flight. A serial
        // executor can never get two in flight at once → maxConcurrent stays 1.
        val tts = ServerTtsBackend { _, _, _ ->
            val now = inFlight.incrementAndGet()
            maxConcurrent.updateAndGet { max(it, now) }
            both.countDown()
            both.await(3, TimeUnit.SECONDS)
            inFlight.decrementAndGet()
            ByteArray(1)
        }
        server = SpeechServer({ _, _ -> "x" }, tts, port = 0).start()

        val pool = Executors.newFixedThreadPool(2)
        try {
            val f1 = pool.submit<Int> { post("/v1/audio/speech", """{"input":"a"}""").statusCode() }
            val f2 = pool.submit<Int> { post("/v1/audio/speech", """{"input":"b"}""").statusCode() }
            assertEquals(200, f1.get(10, TimeUnit.SECONDS))
            assertEquals(200, f2.get(10, TimeUnit.SECONDS))
            assertTrue(maxConcurrent.get() >= 2, "HARM: requests were serialized (a slow one stalls the rest)")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a backend failure does not leak its exception message to the client`() {
        val secret = "INTERNAL /etc/secret path and stack detail"
        val tts = ServerTtsBackend { _, _, _ -> error(secret) }
        server = SpeechServer({ _, _ -> "x" }, tts, port = 0).start()

        val resp = post("/v1/audio/speech", """{"input":"hi"}""")
        assertEquals(500, resp.statusCode())
        assertTrue(secret !in resp.body(), "HARM: backend exception text leaked to the client: ${resp.body()}")
    }
}
