package agents_engine.core

import agents_engine.content.Content
import agents_engine.content.ImageMime
import agents_engine.content.InMemoryBlobStore
import agents_engine.model.VisionFixtures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertTrue

/**
 * #2470 slice b — live tests that exercise `agent.invokeWithAttachments`
 * end-to-end across all four built-in providers. Companion to
 * `VisionLiveTest` (slice a) which hits the raw `ModelClient`; this
 * test pushes the same fixtures through the agent surface so the
 * BlobStore-deref path is exercised on live providers.
 *
 * Cost discipline matches slice a: 256×256 PNGs, `temperature = 0`,
 * `maxTokens = 80`, single-turn. Each provider gated by
 * `assumeTrue` so the suite skips cleanly without keys / reachable
 * Ollama.
 *
 * Model overrides:
 *   AGENTSKT_TEST_OLLAMA_VISION_MODEL  (default qwen3-vl:8b)
 *   AGENTSKT_TEST_CLAUDE_VISION_MODEL  (default claude-haiku-4-5)
 *   AGENTSKT_TEST_OPENAI_VISION_MODEL  (default gpt-4o-mini)
 */
class AgentVisionLiveTest {

    private val ollamaModel = System.getenv("AGENTSKT_TEST_OLLAMA_VISION_MODEL") ?: "qwen3-vl:8b"
    private val claudeModel = System.getenv("AGENTSKT_TEST_CLAUDE_VISION_MODEL") ?: "claude-haiku-4-5"
    private val openaiModel = System.getenv("AGENTSKT_TEST_OPENAI_VISION_MODEL") ?: "gpt-4o-mini"

    // ───────────────────────── Ollama ─────────────────────────

    @Tag("live-llm")
    @Test
    fun `Ollama agent invokeWithAttachments counts the three squares`() {
        assumeTrue(isOllamaReachable(), "skipping: no Ollama at localhost:11434")
        val store = InMemoryBlobStore()
        val ref = store.put(VisionFixtures.threeSquaresPng(), ImageMime.Png.wireMime)
        val a = agent<String, String>("ollama-vision") {
            model { ollama(ollamaModel); temperature = 0.0 }
            blobStore(store)
            skills { skill<String, String>("describe", "") { tools() } }
        }
        val reply = runBlocking {
            a.invokeSuspendWithAttachments(
                input = "How many colored squares are in this image? Answer with just the digit.",
                attachments = listOf(Content.Image(ref, ImageMime.Png)),
            )
        }
        println("[Ollama agent vision] squares → $reply")
        assertSquaresCountedAsThree(reply, "Ollama($ollamaModel) via agent")
    }

    @Tag("live-llm")
    @Test
    fun `Ollama agent invokeWithAttachments identifies the house drawing`() {
        assumeTrue(isOllamaReachable(), "skipping: no Ollama at localhost:11434")
        val store = InMemoryBlobStore()
        val ref = store.put(VisionFixtures.housePng(), ImageMime.Png.wireMime)
        val a = agent<String, String>("ollama-vision") {
            model { ollama(ollamaModel); temperature = 0.0 }
            blobStore(store)
            skills { skill<String, String>("describe", "") { tools() } }
        }
        val reply = runBlocking {
            a.invokeSuspendWithAttachments(
                input = "What is depicted in this image? Answer in one short phrase.",
                attachments = listOf(Content.Image(ref, ImageMime.Png)),
            )
        }
        println("[Ollama agent vision] house → $reply")
        assertSeesHouse(reply, "Ollama($ollamaModel) via agent")
    }

    // ───────────────────────── Anthropic ─────────────────────────

    @Tag("live-cloud-api")
    @Test
    fun `Claude agent invokeWithAttachments counts the three squares`() {
        val apiKey = loadKey("ANTHROPIC_API_KEY", ".secrets/anthropic-key")
        assumeTrue(apiKey != null, "skipping: no Anthropic key")
        val store = InMemoryBlobStore()
        val ref = store.put(VisionFixtures.threeSquaresPng(), ImageMime.Png.wireMime)
        val a = agent<String, String>("claude-vision") {
            model {
                claude(claudeModel)
                this.apiKey = apiKey
                temperature = 0.0
                maxTokens = 80
            }
            blobStore(store)
            skills { skill<String, String>("describe", "") { tools() } }
        }
        val reply = runBlocking {
            a.invokeSuspendWithAttachments(
                input = "How many colored squares are in this image? Answer with just the digit.",
                attachments = listOf(Content.Image(ref, ImageMime.Png)),
            )
        }
        println("[Claude agent vision] squares → $reply")
        assertSquaresCountedAsThree(reply, "Claude($claudeModel) via agent")
    }

    @Tag("live-cloud-api")
    @Test
    fun `Claude agent invokeWithAttachments identifies the house drawing`() {
        val apiKey = loadKey("ANTHROPIC_API_KEY", ".secrets/anthropic-key")
        assumeTrue(apiKey != null, "skipping: no Anthropic key")
        val store = InMemoryBlobStore()
        val ref = store.put(VisionFixtures.housePng(), ImageMime.Png.wireMime)
        val a = agent<String, String>("claude-vision") {
            model {
                claude(claudeModel)
                this.apiKey = apiKey
                temperature = 0.0
                maxTokens = 80
            }
            blobStore(store)
            skills { skill<String, String>("describe", "") { tools() } }
        }
        val reply = runBlocking {
            a.invokeSuspendWithAttachments(
                input = "What is depicted in this image? Answer in one short phrase.",
                attachments = listOf(Content.Image(ref, ImageMime.Png)),
            )
        }
        println("[Claude agent vision] house → $reply")
        assertSeesHouse(reply, "Claude($claudeModel) via agent")
    }

    // ───────────────────────── OpenAI ─────────────────────────

    @Tag("live-cloud-api")
    @Test
    fun `OpenAI agent invokeWithAttachments counts the three squares`() {
        val apiKey = loadKey("OPENAI_API_KEY", ".secrets/openai-key")
        assumeTrue(apiKey != null, "skipping: no OpenAI key")
        val store = InMemoryBlobStore()
        val ref = store.put(VisionFixtures.threeSquaresPng(), ImageMime.Png.wireMime)
        val a = agent<String, String>("openai-vision") {
            model {
                openai(openaiModel)
                this.apiKey = apiKey
                temperature = 0.0
                maxTokens = 80
            }
            blobStore(store)
            skills { skill<String, String>("describe", "") { tools() } }
        }
        val reply = runBlocking {
            a.invokeSuspendWithAttachments(
                input = "How many colored squares are in this image? Answer with just the digit.",
                attachments = listOf(Content.Image(ref, ImageMime.Png)),
            )
        }
        println("[OpenAI agent vision] squares → $reply")
        assertSquaresCountedAsThree(reply, "OpenAI($openaiModel) via agent")
    }

    @Tag("live-cloud-api")
    @Test
    fun `OpenAI agent invokeWithAttachments identifies the house drawing`() {
        val apiKey = loadKey("OPENAI_API_KEY", ".secrets/openai-key")
        assumeTrue(apiKey != null, "skipping: no OpenAI key")
        val store = InMemoryBlobStore()
        val ref = store.put(VisionFixtures.housePng(), ImageMime.Png.wireMime)
        val a = agent<String, String>("openai-vision") {
            model {
                openai(openaiModel)
                this.apiKey = apiKey
                temperature = 0.0
                maxTokens = 80
            }
            blobStore(store)
            skills { skill<String, String>("describe", "") { tools() } }
        }
        val reply = runBlocking {
            a.invokeSuspendWithAttachments(
                input = "What is depicted in this image? Answer in one short phrase.",
                attachments = listOf(Content.Image(ref, ImageMime.Png)),
            )
        }
        println("[OpenAI agent vision] house → $reply")
        assertSeesHouse(reply, "OpenAI($openaiModel) via agent")
    }

    // ───────────────────────── Helpers ─────────────────────────

    private fun assertSquaresCountedAsThree(reply: String, providerLabel: String) {
        val lowered = reply.lowercase()
        val sees3 = "3" in reply || "three" in lowered
        assertTrue(sees3, "$providerLabel did not count three squares; got: $reply")
    }

    private fun assertSeesHouse(reply: String, providerLabel: String) {
        val lowered = reply.lowercase()
        val sees = listOf("house", "home", "cottage", "building", "cabin", "barn").any { it in lowered }
        assertTrue(sees, "$providerLabel did not recognise the house drawing; got: $reply")
    }

    private fun loadKey(envVar: String, secretFile: String): String? {
        val envKey = System.getenv(envVar)
        if (!envKey.isNullOrBlank()) return envKey
        val file = File(secretFile)
        return if (file.exists()) file.readText().trim().ifBlank { null } else null
    }

    private fun isOllamaReachable(): Boolean = try {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:11434/api/tags"))
            .timeout(Duration.ofMillis(1500))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        response.statusCode() in 200..299
    } catch (_: Throwable) {
        false
    }
}
