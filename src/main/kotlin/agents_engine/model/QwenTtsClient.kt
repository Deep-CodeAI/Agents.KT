package agents_engine.model

import agents_engine.content.AudioMime
import agents_engine.content.BlobStore
import agents_engine.content.Content
import agents_engine.mcp.McpJson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * #4501 — **self-hosted Qwen TTS** text-to-speech. Targets the OpenAI-compatible
 * `POST {baseUrl}/v1/audio/speech` (JSON) endpoint that self-hosted TTS servers
 * expose — openedai-speech, LocalAI, Speaches — behind which a Qwen-TTS voice is
 * served. No API key by default (pass [bearerToken] only for a fronting gateway);
 * [baseUrl] is required. Synthesized bytes land in [blobStore]; the typed
 * [Content.Audio] ref travels (so it survives audit + snapshot).
 *
 * [responseFormat] picks the wire audio format and the resulting [AudioMime]
 * (`mp3`/`wav`/`flac`/`opus`). Implements [TtsModelClient], so it is a drop-in swap
 * for the OpenAI adapter wherever a `TtsModelClient` is expected (the `speak` tool,
 * #4501).
 */
class QwenTtsClient(
    private val baseUrl: String,
    private val blobStore: BlobStore,
    private val model: String = "qwen-tts",
    private val voice: String = "Cherry",
    private val responseFormat: String = "wav",
    private val bearerToken: String? = null,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : TtsModelClient {

    private val timeout = Duration.ofSeconds(timeoutSeconds)

    /** Map the requested wire format to a typed [AudioMime]; fail fast on anything we can't tag. */
    private val mime: AudioMime = when (responseFormat.lowercase()) {
        "mp3" -> AudioMime.Mp3
        "wav" -> AudioMime.Wav
        "flac" -> AudioMime.Flac
        "opus", "ogg" -> AudioMime.Ogg
        else -> error("Unsupported Qwen TTS responseFormat '$responseFormat' (use mp3/wav/flac/opus).")
    }

    override fun speak(text: String): Content.Audio {
        val body = """{"model":${McpJson.encode(model)},"input":${McpJson.encode(text)},""" +
            """"voice":${McpJson.encode(voice)},"response_format":${McpJson.encode(responseFormat)}}"""
        val builder = HttpRequest.newBuilder(URI.create("$baseUrl/v1/audio/speech"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        bearerToken?.let { builder.header("Authorization", "Bearer $it") }
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() == HTTP_OK) {
            "Qwen TTS returned HTTP ${response.statusCode()}: ${String(response.body()).take(ERROR_PREVIEW)}"
        }
        val ref = blobStore.put(response.body(), mime.wireMime)
        return Content.Audio(ref = ref, mime = mime)
    }

    private companion object {
        const val HTTP_OK = 200
        const val ERROR_PREVIEW = 200
        const val DEFAULT_TIMEOUT_SECONDS = 120L
    }
}
