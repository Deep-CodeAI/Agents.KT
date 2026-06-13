package agents_engine.model

import agents_engine.content.AudioMime
import agents_engine.content.BlobStore
import agents_engine.content.Content
import agents_engine.generation.LenientJsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * #4501 — **self-hosted Whisper** speech-to-text. Targets the OpenAI-compatible
 * `POST {baseUrl}/v1/audio/transcriptions` (multipart) endpoint that the common
 * self-hosted Whisper servers expose — faster-whisper-server, Speaches, LocalAI,
 * whisper.cpp's OpenAI shim. Unlike [OpenAiSpeechToTextClient] there is **no API
 * key by default** (self-hosted servers are usually unauthenticated); pass
 * [bearerToken] only when a gateway in front of the server requires one. [baseUrl]
 * is required — there is no hosted default to fall back to.
 *
 * Implements [SpeechToTextClient], so it is a drop-in swap for the OpenAI adapter
 * wherever a `SpeechToTextClient` is expected (including the `transcribe_audio`
 * tool, #4501).
 */
class WhisperSttClient(
    private val baseUrl: String,
    private val model: String = "whisper-1",
    private val bearerToken: String? = null,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : SpeechToTextClient {

    private val timeout = Duration.ofSeconds(timeoutSeconds)

    override fun transcribe(audio: Content.Audio, blobStore: BlobStore): String {
        val bytes = blobStore.get(audio.ref)
            ?: error("Audio ref ${audio.ref.hash} not found in the supplied BlobStore.")
        val boundary = "agents-kt-${audio.ref.hash.take(BOUNDARY_HASH_CHARS)}"
        val body = multipartBody(boundary, bytes, audio.mime)
        val builder = HttpRequest.newBuilder(URI.create("$baseUrl/v1/audio/transcriptions"))
            .timeout(timeout)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        bearerToken?.let { builder.header("Authorization", "Bearer $it") }
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == HTTP_OK) {
            "Whisper transcription returned HTTP ${response.statusCode()}: ${response.body().take(ERROR_PREVIEW)}"
        }
        val parsed = LenientJsonParser.parse(response.body()) as? Map<*, *>
            ?: error("Whisper transcription returned non-JSON: ${response.body().take(ERROR_PREVIEW)}")
        return parsed["text"] as? String
            ?: error("Whisper transcription response missing text: $parsed")
    }

    private fun multipartBody(boundary: String, bytes: ByteArray, mime: AudioMime): ByteArray {
        val extension = when (mime) {
            AudioMime.Mp3 -> "mp3"
            AudioMime.Wav -> "wav"
            AudioMime.Flac -> "flac"
            AudioMime.Ogg -> "ogg"
        }
        val head = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"model\"\r\n\r\n$model\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"audio.$extension\"\r\n" +
                "Content-Type: ${mime.wireMime}\r\n\r\n"
            ).toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        return head + bytes + tail
    }

    private companion object {
        const val HTTP_OK = 200
        const val ERROR_PREVIEW = 200
        const val DEFAULT_TIMEOUT_SECONDS = 120L
        const val BOUNDARY_HASH_CHARS = 16
    }
}
