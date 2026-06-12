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
 * #3867.a — OpenAI Whisper adapter (`POST /v1/audio/transcriptions`,
 * multipart). Resolves the [Content.Audio] bytes through the caller's
 * [BlobStore] and returns the transcript. `baseUrl` is injectable for
 * stub-server tests.
 */
class OpenAiSpeechToTextClient(
    private val apiKey: String,
    private val model: String = "whisper-1",
    private val baseUrl: String = "https://api.openai.com",
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : SpeechToTextClient {

    private val timeout = Duration.ofSeconds(timeoutSeconds)

    override fun transcribe(audio: Content.Audio, blobStore: BlobStore): String {
        val bytes = blobStore.get(audio.ref)
            ?: error("Audio ref ${audio.ref.hash} not found in the supplied BlobStore.")
        val boundary = "agents-kt-${audio.ref.hash.take(BOUNDARY_HASH_CHARS)}"
        val body = multipartBody(boundary, bytes, audio.mime)
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/audio/transcriptions"))
            .timeout(timeout)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == HTTP_OK) {
            "OpenAI transcription returned HTTP ${response.statusCode()}: ${response.body().take(ERROR_PREVIEW)}"
        }
        val parsed = LenientJsonParser.parse(response.body()) as? Map<*, *>
            ?: error("OpenAI transcription returned non-JSON: ${response.body().take(ERROR_PREVIEW)}")
        return parsed["text"] as? String
            ?: error("OpenAI transcription response missing text: $parsed")
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
