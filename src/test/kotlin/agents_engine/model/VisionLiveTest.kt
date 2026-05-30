package agents_engine.model

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #2470 — live vision-input integration tests across all four
 * providers. Each test sends a programmatically-generated PNG (a
 * 3-coloured-square image OR a simple house) to a vision-capable
 * model and checks the response.
 *
 * **Cost discipline:**
 * - 256×256 PNGs (~5 KB base64) — small payloads, fast roundtrip.
 * - `temperature = 0`, `maxTokens = 80` — short deterministic replies.
 * - Single-turn — no tool calls, no follow-ups.
 * - Each provider's tag is skipped cleanly when the API key / model
 *   isn't reachable (assumeTrue gate).
 *
 * **Tags:**
 * - `live-llm` — Ollama (local + cloud-via-Ollama-Cloud). Excluded
 *   from default `./gradlew test`; runs via `./gradlew integrationTest`.
 * - `live-cloud-api` — direct cloud APIs (Anthropic, OpenAI, DeepSeek).
 *   In default `./gradlew test`; `assumeTrue` skips when no key.
 *
 * **DeepSeek:** most DeepSeek models don't have vision today. The
 * adapter inherits OpenAI's image-content shape; the field passes
 * through and the model silently ignores it. We test the shape via
 * the unit tests (`VisionWireFormatTest`) — no live call here to save
 * cost on what is effectively a no-op.
 *
 * **Assertion shape:** loose — every cheap vision model has some
 * variance in phrasing. The test passes if the answer mentions any of
 * a small set of acceptable keywords. Goal is "did the image reach
 * the model and elicit a sensible reply", not "did the model phrase
 * it exactly this way."
 */
class VisionLiveTest {

    private val ollamaVisionModel: String =
        System.getenv("AGENTSKT_TEST_OLLAMA_VISION_MODEL") ?: "qwen3-vl:8b"
    private val claudeVisionModel: String =
        System.getenv("AGENTSKT_TEST_CLAUDE_VISION_MODEL") ?: "claude-haiku-4-5"
    private val openaiVisionModel: String =
        System.getenv("AGENTSKT_TEST_OPENAI_VISION_MODEL") ?: "gpt-4o-mini"

    // ─────────────────────────── Ollama (qwen3-vl) ──────────────────────

    @Tag("live-llm")
    @Test
    fun `Ollama qwen3-vl counts the three squares in a generated image`() {
        assumeTrue(isOllamaReachable(), "skipping: no Ollama at localhost:11434")
        val client = OllamaClient(model = ollamaVisionModel, temperature = 0.0)
        val png = VisionFixtures.threeSquaresPng()
        val response = client.chat(
            listOf(
                LlmMessage(
                    role = "user",
                    content = "How many colored squares are in this image? Answer with just the digit.",
                    images = listOf(ImagePart(VisionFixtures.toBase64(png), ImagePart.WireMime.Png)),
                ),
            ),
        )
        val text = textOf(response)
        println("[Ollama vision] squares → $text")
        assertSquaresCountedAsThree(text, "Ollama($ollamaVisionModel)")
    }

    @Tag("live-llm")
    @Test
    fun `Ollama qwen3-vl identifies the simple house drawing`() {
        assumeTrue(isOllamaReachable(), "skipping: no Ollama at localhost:11434")
        val client = OllamaClient(model = ollamaVisionModel, temperature = 0.0)
        val png = VisionFixtures.housePng()
        val response = client.chat(
            listOf(
                LlmMessage(
                    role = "user",
                    content = "What is depicted in this image? Answer in one short phrase.",
                    images = listOf(ImagePart(VisionFixtures.toBase64(png), ImagePart.WireMime.Png)),
                ),
            ),
        )
        val text = textOf(response)
        println("[Ollama vision] house → $text")
        assertSeesHouse(text, "Ollama($ollamaVisionModel)")
    }

    // ─────────────────────────── Anthropic Haiku ────────────────────────

    @Tag("live-cloud-api")
    @Test
    fun `Claude Haiku counts the three squares`() {
        val apiKey = loadKey("ANTHROPIC_API_KEY", ".secrets/anthropic-key")
        assumeTrue(apiKey != null, "skipping: no Anthropic key")
        val client = ClaudeClient(apiKey = apiKey!!, model = claudeVisionModel, temperature = 0.0, maxTokens = 80)
        val png = VisionFixtures.threeSquaresPng()
        val response = client.chat(
            listOf(
                LlmMessage(
                    role = "user",
                    content = "How many colored squares are in this image? Answer with just the digit.",
                    images = listOf(ImagePart(VisionFixtures.toBase64(png), ImagePart.WireMime.Png)),
                ),
            ),
        )
        val text = textOf(response)
        println("[Claude vision] squares → $text")
        assertSquaresCountedAsThree(text, "Claude($claudeVisionModel)")
    }

    @Tag("live-cloud-api")
    @Test
    fun `Claude Haiku identifies the simple house drawing`() {
        val apiKey = loadKey("ANTHROPIC_API_KEY", ".secrets/anthropic-key")
        assumeTrue(apiKey != null, "skipping: no Anthropic key")
        val client = ClaudeClient(apiKey = apiKey!!, model = claudeVisionModel, temperature = 0.0, maxTokens = 80)
        val png = VisionFixtures.housePng()
        val response = client.chat(
            listOf(
                LlmMessage(
                    role = "user",
                    content = "What is depicted in this image? Answer in one short phrase.",
                    images = listOf(ImagePart(VisionFixtures.toBase64(png), ImagePart.WireMime.Png)),
                ),
            ),
        )
        val text = textOf(response)
        println("[Claude vision] house → $text")
        assertSeesHouse(text, "Claude($claudeVisionModel)")
    }

    // ─────────────────────────── OpenAI gpt-4o-mini ─────────────────────

    @Tag("live-cloud-api")
    @Test
    fun `OpenAI gpt-4o-mini counts the three squares`() {
        val apiKey = loadKey("OPENAI_API_KEY", ".secrets/openai-key")
        assumeTrue(apiKey != null, "skipping: no OpenAI key")
        val client = OpenAiClient(apiKey = apiKey!!, model = openaiVisionModel, temperature = 0.0, maxTokens = 80)
        val png = VisionFixtures.threeSquaresPng()
        val response = client.chat(
            listOf(
                LlmMessage(
                    role = "user",
                    content = "How many colored squares are in this image? Answer with just the digit.",
                    images = listOf(ImagePart(VisionFixtures.toBase64(png), ImagePart.WireMime.Png)),
                ),
            ),
        )
        val text = textOf(response)
        println("[OpenAI vision] squares → $text")
        assertSquaresCountedAsThree(text, "OpenAI($openaiVisionModel)")
    }

    @Tag("live-cloud-api")
    @Test
    fun `OpenAI gpt-4o-mini identifies the simple house drawing`() {
        val apiKey = loadKey("OPENAI_API_KEY", ".secrets/openai-key")
        assumeTrue(apiKey != null, "skipping: no OpenAI key")
        val client = OpenAiClient(apiKey = apiKey!!, model = openaiVisionModel, temperature = 0.0, maxTokens = 80)
        val png = VisionFixtures.housePng()
        val response = client.chat(
            listOf(
                LlmMessage(
                    role = "user",
                    content = "What is depicted in this image? Answer in one short phrase.",
                    images = listOf(ImagePart(VisionFixtures.toBase64(png), ImagePart.WireMime.Png)),
                ),
            ),
        )
        val text = textOf(response)
        println("[OpenAI vision] house → $text")
        assertSeesHouse(text, "OpenAI($openaiVisionModel)")
    }

    // ───────────────────────────── Helpers ──────────────────────────────

    private fun textOf(response: LlmResponse): String = when (response) {
        is LlmResponse.Text -> response.content
        is LlmResponse.ToolCalls -> error("vision call unexpectedly returned tool_calls: $response")
    }

    private fun assertSquaresCountedAsThree(text: String, providerLabel: String) {
        val lowered = text.lowercase()
        val sees3 = "3" in text || "three" in lowered
        assertTrue(sees3, "$providerLabel did not count three squares; got: $text")
    }

    private fun assertSeesHouse(text: String, providerLabel: String) {
        val lowered = text.lowercase()
        val sees = listOf("house", "home", "cottage", "building", "cabin", "barn").any { it in lowered }
        assertTrue(sees, "$providerLabel did not recognise the house drawing; got: $text")
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
