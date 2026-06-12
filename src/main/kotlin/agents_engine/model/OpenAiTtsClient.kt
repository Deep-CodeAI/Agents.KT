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
 * #3867.c — OpenAI TTS adapter (`POST /v1/audio/speech`, mp3). Synthesized
 * bytes land in [blobStore]; the typed [Content.Audio] ref travels.
 * `baseUrl` is injectable for stub-server tests.
 */
class OpenAiTtsClient(
    private val apiKey: String,
    private val blobStore: BlobStore,
    private val model: String = "gpt-4o-mini-tts",
    private val voice: String = "alloy",
    private val baseUrl: String = "https://api.openai.com",
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : TtsModelClient {

    private val timeout = Duration.ofSeconds(timeoutSeconds)

    override fun speak(text: String): Content.Audio {
        val body = """{"model":${McpJson.encode(model)},"input":${McpJson.encode(text)},""" +
            """"voice":${McpJson.encode(voice)},"response_format":"mp3"}"""
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/audio/speech"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() == HTTP_OK) {
            "OpenAI TTS returned HTTP ${response.statusCode()}: ${String(response.body()).take(ERROR_PREVIEW)}"
        }
        val ref = blobStore.put(response.body(), AudioMime.Mp3.wireMime)
        return Content.Audio(ref = ref, mime = AudioMime.Mp3)
    }

    private companion object {
        const val HTTP_OK = 200
        const val ERROR_PREVIEW = 200
        const val DEFAULT_TIMEOUT_SECONDS = 120L
    }
}
