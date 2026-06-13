package agents_engine.speechserver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * #4506 — a pure-JDK, OpenAI-compatible speech server. No Docker, no Python, no
 * external dependency: just `com.sun.net.httpserver` over two pluggable backend
 * seams. Run it with `java -jar` (see [main][SpeechServerMainKt]) and point any
 * OpenAI-compatible client — including Agents.KT's `WhisperSttClient` /
 * `QwenTtsClient` — at it.
 *
 * Endpoints (subset of the OpenAI audio API):
 *  - `POST /v1/audio/transcriptions` — multipart (`file` + `model`) → `{"text": …}`
 *  - `POST /v1/audio/speech` — JSON (`input`, `voice`, `response_format`) → audio bytes
 *
 * The server owns the wire; [stt] and [tts] own the inference. Bind a pure-jar
 * backend (whisper-jni STT, sherpa-onnx TTS) or proxy a remote one.
 */
class SpeechServer(
    private val stt: ServerSttBackend,
    private val tts: ServerTtsBackend,
    port: Int = 0,
    host: String = "127.0.0.1",
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0).apply {
        createContext("/v1/audio/transcriptions") { ex -> guard(ex) { handleTranscription(ex) } }
        createContext("/v1/audio/speech") { ex -> guard(ex) { handleSpeech(ex) } }
        executor = null
    }

    /** The bound port (useful when constructed with port 0 for an ephemeral port). */
    val port: Int get() = server.address.port

    fun start(): SpeechServer = apply { server.start() }
    fun stop(delaySeconds: Int = 0) = server.stop(delaySeconds)

    private fun handleTranscription(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respondJson(ex, HTTP_METHOD_NOT_ALLOWED, errorJson("POST required"))
        val contentType = ex.requestHeaders.getFirst("Content-Type").orEmpty()
        val boundary = Regex("boundary=([^;]+)").find(contentType)?.groupValues?.get(1)?.trim('"')
            ?: return respondJson(ex, HTTP_BAD_REQUEST, errorJson("expected multipart/form-data with a boundary"))
        val file = parseMultipart(ex.requestBody.readBytes(), boundary).firstOrNull { it.name == "file" }
            ?: return respondJson(ex, HTTP_BAD_REQUEST, errorJson("multipart missing a 'file' part"))
        val transcript = stt.transcribe(file.content, file.contentType ?: "application/octet-stream")
        respondJson(ex, HTTP_OK, """{"text":"${jsonEscape(transcript)}"}""")
    }

    private fun handleSpeech(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respondJson(ex, HTTP_METHOD_NOT_ALLOWED, errorJson("POST required"))
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val input = jsonStringField(body, "input")
            ?: return respondJson(ex, HTTP_BAD_REQUEST, errorJson("missing 'input'"))
        val format = jsonStringField(body, "response_format") ?: "wav"
        val audio = tts.synthesize(input, jsonStringField(body, "voice"), format)
        ex.responseHeaders.add("Content-Type", audioContentType(format))
        ex.sendResponseHeaders(HTTP_OK, audio.size.toLong())
        ex.responseBody.use { it.write(audio) }
        ex.close()
    }

    private fun guard(ex: HttpExchange, block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            respondJson(ex, HTTP_BAD_REQUEST, errorJson(e.message ?: "bad request"))
        } catch (e: IllegalStateException) {
            respondJson(ex, HTTP_SERVER_ERROR, errorJson(e.message ?: "backend error"))
        }
    }

    private fun respondJson(ex: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
        ex.close()
    }

    private fun errorJson(message: String) = """{"error":{"message":"${jsonEscape(message)}"}}"""

    private fun audioContentType(format: String): String = when (format.lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "opus", "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HTTP_SERVER_ERROR = 500
    }
}
