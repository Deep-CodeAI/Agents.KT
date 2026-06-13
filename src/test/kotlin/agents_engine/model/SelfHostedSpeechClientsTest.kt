package agents_engine.model

import agents_engine.content.AudioMime
import agents_engine.content.Content
import agents_engine.content.InMemoryBlobStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #4501 — self-hosted Whisper (STT) + Qwen (TTS) adapters against a local stub
// server speaking the OpenAI-compatible wire (/v1/audio/transcriptions,
// /v1/audio/speech). Pins: no auth header by default, bytes round-trip the
// BlobStore, format → typed AudioMime.

class SelfHostedSpeechClientsTest {

    private val requests = mutableListOf<Triple<String, ByteArray, String?>>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/audio/transcriptions") { ex ->
            record(ex)
            respond(ex, """{"text":"the meeting is at noon"}""".toByteArray(), "application/json")
        }
        createContext("/v1/audio/speech") { ex ->
            record(ex)
            respond(ex, "wav-bytes".toByteArray(), "audio/wav")
        }
        executor = null
        start()
    }

    private fun record(ex: HttpExchange) {
        val auth = ex.requestHeaders.getFirst("Authorization")
        requests.add(Triple(ex.requestURI.path, ex.requestBody.readBytes(), auth))
    }

    private fun respond(ex: HttpExchange, body: ByteArray, mime: String) {
        ex.responseHeaders.add("Content-Type", mime)
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
        ex.close()
    }

    private val baseUrl get() = "http://localhost:${server.address.port}"

    @AfterTest fun stop() = server.stop(0)

    @Test
    fun `whisper transcribes and sends no auth header by default`() {
        val blobs = InMemoryBlobStore()
        val ref = blobs.put("RIFFdata".toByteArray(), AudioMime.Wav.wireMime)
        val transcript = WhisperSttClient(baseUrl)
            .transcribe(Content.Audio(ref = ref, mime = AudioMime.Wav), blobs)

        assertEquals("the meeting is at noon", transcript)
        val (path, body, auth) = requests.single()
        assertEquals("/v1/audio/transcriptions", path)
        assertEquals(null, auth, "self-hosted Whisper sends no Authorization by default")
        val multipart = String(body, Charsets.ISO_8859_1)
        assertTrue("filename=\"audio.wav\"" in multipart && "RIFFdata" in multipart, "carries the audio bytes")
    }

    @Test
    fun `whisper attaches a bearer token only when given one`() {
        val blobs = InMemoryBlobStore()
        val ref = blobs.put("x".toByteArray(), AudioMime.Mp3.wireMime)
        WhisperSttClient(baseUrl, bearerToken = "gw-token")
            .transcribe(Content.Audio(ref = ref, mime = AudioMime.Mp3), blobs)
        assertEquals("Bearer gw-token", requests.single().third)
    }

    @Test
    fun `qwen tts returns typed Content Audio with bytes in the store and no auth by default`() {
        val blobs = InMemoryBlobStore()
        val audio = QwenTtsClient(baseUrl, blobs, voice = "Cherry").speak("hello there")

        assertEquals(AudioMime.Wav, audio.mime)
        assertEquals("wav-bytes", String(blobs.get(audio.ref)!!), "synthesized bytes land in the store")
        val (path, body, auth) = requests.single()
        assertEquals("/v1/audio/speech", path)
        assertEquals(null, auth)
        val json = String(body)
        assertTrue("\"input\":\"hello there\"" in json && "\"voice\":\"Cherry\"" in json, json)
        assertTrue("\"response_format\":\"wav\"" in json, json)
    }

    @Test
    fun `qwen tts rejects an untaggable response format`() {
        val blobs = InMemoryBlobStore()
        val ex = runCatching { QwenTtsClient(baseUrl, blobs, responseFormat = "aac") }.exceptionOrNull()
        assertTrue(ex is IllegalStateException && "aac" in ex.message.orEmpty(), "got: $ex")
    }

    // ─── #4504 DX: preflight + fail-fast actionable errors ───

    @Test
    fun `preflight passes against a live endpoint`() {
        WhisperSttClient(baseUrl).preflight()
        QwenTtsClient(baseUrl, InMemoryBlobStore()).preflight()
    }

    @Test
    fun `preflight against a dead endpoint throws an actionable message`() {
        val dead = "http://127.0.0.1:1"
        val ex = runCatching { WhisperSttClient(dead).preflight() }.exceptionOrNull()
        assertTrue(ex is IllegalStateException, "got: $ex")
        assertTrue("/v1/audio/transcriptions" in ex.message.orEmpty(), "remediation names the endpoint: ${ex?.message}")
    }

    @Test
    fun `transcribe against a dead server fails fast with remediation, not a raw ConnectException`() {
        val blobs = InMemoryBlobStore()
        val ref = blobs.put("x".toByteArray(), AudioMime.Wav.wireMime)
        val ex = runCatching {
            WhisperSttClient("http://127.0.0.1:1").transcribe(Content.Audio(ref = ref, mime = AudioMime.Wav), blobs)
        }.exceptionOrNull()
        assertTrue(ex is IllegalStateException, "wrapped, not raw: $ex")
        val msg = ex?.message.orEmpty()
        assertTrue("cannot reach a server" in msg && "baseUrl" in msg, msg)
    }

    @Test
    fun `speak against a dead server fails fast with remediation`() {
        val ex = runCatching {
            QwenTtsClient("http://127.0.0.1:1", InMemoryBlobStore()).speak("hi")
        }.exceptionOrNull()
        assertTrue(ex is IllegalStateException && "cannot reach a server" in ex.message.orEmpty(), "${ex?.message}")
    }
}
