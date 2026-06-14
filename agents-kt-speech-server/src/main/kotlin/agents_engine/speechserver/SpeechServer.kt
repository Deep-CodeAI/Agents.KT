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
    /** #4508 — max accepted request-body size; larger requests get 413 (no unbounded read). */
    private val maxRequestBytes: Long = DEFAULT_MAX_REQUEST_BYTES,
    /** #4508 — bind a non-loopback host (exposes the unauthenticated server) only when explicitly opted in. */
    allowNonLoopback: Boolean = false,
) {
    init {
        // #4508 — refuse to expose the unauthenticated server off loopback unless asked. Runs
        // BEFORE `server` below, so a rejected host never opens a socket.
        require(allowNonLoopback || isLoopbackHost(host)) {
            "SpeechServer refuses to bind non-loopback host '$host' — the server is unauthenticated. " +
                "Pass allowNonLoopback = true to expose it deliberately (and put auth in front)."
        }
    }

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
        val raw = readBody(ex) ?: return
        val file = parseMultipart(raw, boundary).firstOrNull { it.name == "file" }
            ?: return respondJson(ex, HTTP_BAD_REQUEST, errorJson("multipart missing a 'file' part"))
        val transcript = stt.transcribe(file.content, file.contentType ?: "application/octet-stream")
        respondJson(ex, HTTP_OK, """{"text":"${jsonEscape(transcript)}"}""")
    }

    private fun handleSpeech(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respondJson(ex, HTTP_METHOD_NOT_ALLOWED, errorJson("POST required"))
        val body = (readBody(ex) ?: return).toString(Charsets.UTF_8)
        val input = jsonStringField(body, "input")
            ?: return respondJson(ex, HTTP_BAD_REQUEST, errorJson("missing 'input'"))
        val format = jsonStringField(body, "response_format") ?: "wav"
        val audio = tts.synthesize(input, jsonStringField(body, "voice"), format)
        ex.responseHeaders.add("Content-Type", audioContentType(format))
        ex.sendResponseHeaders(HTTP_OK, audio.size.toLong())
        ex.responseBody.use { it.write(audio) }
        ex.close()
    }

    /**
     * #4508 — read the request body with a hard [maxRequestBytes] cap. Rejects an over-cap
     * declared `Content-Length` up front, and stops reading (413) the moment a chunked/unsized
     * stream crosses the cap — so a hostile client can't exhaust memory. Returns null after it
     * has already sent the 413 response.
     */
    private fun readBody(ex: HttpExchange): ByteArray? {
        val declared = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > maxRequestBytes) return rejectTooLarge(ex)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var total = 0L
        while (true) {
            val read = ex.requestBody.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxRequestBytes) return rejectTooLarge(ex)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Send 413 and return null (so callers `?: return` out of the handler). */
    private fun rejectTooLarge(ex: HttpExchange): ByteArray? {
        respondJson(ex, HTTP_PAYLOAD_TOO_LARGE, errorJson("request body exceeds the $maxRequestBytes-byte limit"))
        return null
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
        const val HTTP_PAYLOAD_TOO_LARGE = 413
        const val HTTP_SERVER_ERROR = 500
        const val DEFAULT_MAX_REQUEST_BYTES = 25L * 1024 * 1024
        const val READ_CHUNK_BYTES = 8192
    }
}

/**
 * #4508 — is [host] a loopback address? Bare-string fast paths first, then a resolve.
 * `0.0.0.0` (the wildcard / all-interfaces bind) is NOT loopback.
 */
internal fun isLoopbackHost(host: String): Boolean = when (host.lowercase()) {
    "localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1" -> true
    else -> runCatching { java.net.InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
}
