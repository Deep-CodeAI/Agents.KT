package agents_engine.model

import agents_engine.generation.LenientJsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2470 — vision-input wire-format tests. Pins the per-provider JSON
 * shape so an adapter regression surfaces at unit-test time, not at
 * provider HTTP time. No network — uses each client's
 * `buildRequestJson` and parses the result.
 *
 * Each provider's image payload is verified for: text-prompt
 * preservation, image-block presence, base64 splatting, typed mime
 * propagation. Pre-#2470 message shapes (no images) stay byte-identical.
 */
class VisionWireFormatTest {

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2)
    private val pngBase64 = java.util.Base64.getEncoder().encodeToString(pngBytes)

    private fun userMessage(text: String, images: List<ImagePart>? = null) =
        LlmMessage(role = "user", content = text, images = images)

    // ────────────────────────────── Ollama ──────────────────────────────

    @Test
    fun `Ollama user message with images emits images array of base64 strings`() {
        val client = OllamaClient(model = "qwen3-vl:8b")
        val body = client.buildRequestJson(listOf(
            userMessage("How many squares?", listOf(ImagePart(pngBase64, ImagePart.WireMime.Png))),
        ))
        @Suppress("UNCHECKED_CAST")
        val parsed = LenientJsonParser.parse(body) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val messages = parsed["messages"] as List<Map<String, Any?>>
        val userMsg = messages.single()
        assertEquals("user", userMsg["role"])
        assertEquals("How many squares?", userMsg["content"], "text prompt preserved")
        @Suppress("UNCHECKED_CAST")
        val images = userMsg["images"] as List<String>
        assertEquals(1, images.size)
        assertEquals(pngBase64, images[0], "base64 splatted verbatim, no data: prefix")
    }

    @Test
    fun `Ollama user message without images omits the images field (back-compat)`() {
        val client = OllamaClient(model = "llama3.2")
        val body = client.buildRequestJson(listOf(userMessage("plain text")))
        assertTrue("\"images\"" !in body, "no images field when not requested: $body")
    }

    @Test
    fun `Ollama non-user message with images on it does NOT emit images (system_assistant_tool ignore)`() {
        val client = OllamaClient(model = "qwen3-vl:8b")
        val body = client.buildRequestJson(listOf(
            LlmMessage(role = "system", content = "be helpful",
                images = listOf(ImagePart(pngBase64, ImagePart.WireMime.Png))),
        ))
        assertTrue("\"images\"" !in body, "non-user roles never carry images on the wire: $body")
    }

    // ────────────────────────────── Anthropic ───────────────────────────

    @Test
    fun `Claude user message with images emits content array with text plus image blocks`() {
        val client = ClaudeClient(apiKey = "test", model = "claude-haiku-4-5")
        val body = client.buildRequestJson(listOf(
            userMessage("Identify this image.", listOf(ImagePart(pngBase64, ImagePart.WireMime.Png))),
        ))
        @Suppress("UNCHECKED_CAST")
        val parsed = LenientJsonParser.parse(body) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val messages = parsed["messages"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val content = messages.single()["content"] as List<Map<String, Any?>>
        assertEquals(2, content.size, "one text block + one image block")
        assertEquals("text", content[0]["type"])
        assertEquals("Identify this image.", content[0]["text"])
        assertEquals("image", content[1]["type"])
        @Suppress("UNCHECKED_CAST")
        val source = content[1]["source"] as Map<String, Any?>
        assertEquals("base64", source["type"])
        assertEquals("image/png", source["media_type"], "typed mime → wire media_type")
        assertEquals(pngBase64, source["data"])
    }

    @Test
    fun `Claude user message without images stays on the legacy string-content path`() {
        val client = ClaudeClient(apiKey = "test", model = "claude-haiku-4-5")
        val body = client.buildRequestJson(listOf(userMessage("just text please")))
        @Suppress("UNCHECKED_CAST")
        val parsed = LenientJsonParser.parse(body) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val messages = parsed["messages"] as List<Map<String, Any?>>
        // Legacy shape: content is a string, not an array.
        assertEquals("just text please", messages.single()["content"], "back-compat: string content when no images")
    }

    // ────────────────────────────── OpenAI ──────────────────────────────

    @Test
    fun `OpenAI user message with images emits content array with text plus image_url blocks`() {
        val client = OpenAiClient(apiKey = "test", model = "gpt-4o-mini")
        val body = client.buildRequestJson(listOf(
            userMessage("What is in this picture?", listOf(ImagePart(pngBase64, ImagePart.WireMime.Png))),
        ))
        @Suppress("UNCHECKED_CAST")
        val parsed = LenientJsonParser.parse(body) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val messages = parsed["messages"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val content = messages.single()["content"] as List<Map<String, Any?>>
        assertEquals(2, content.size)
        assertEquals("text", content[0]["type"])
        assertEquals("What is in this picture?", content[0]["text"])
        assertEquals("image_url", content[1]["type"])
        @Suppress("UNCHECKED_CAST")
        val imageUrl = content[1]["image_url"] as Map<String, Any?>
        val url = imageUrl["url"] as String
        assertTrue(url.startsWith("data:image/png;base64,"), "data-URL with typed mime: $url")
        assertTrue(url.endsWith(pngBase64), "base64 splatted verbatim at the end: $url")
    }

    @Test
    fun `OpenAI user message without images stays on the legacy string-content path`() {
        val client = OpenAiClient(apiKey = "test", model = "gpt-4o-mini")
        val body = client.buildRequestJson(listOf(userMessage("just text")))
        @Suppress("UNCHECKED_CAST")
        val parsed = LenientJsonParser.parse(body) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val messages = parsed["messages"] as List<Map<String, Any?>>
        assertEquals("just text", messages.single()["content"], "back-compat: string content when no images")
    }

    @Test
    fun `OpenAI multiple images each get their own image_url block`() {
        val client = OpenAiClient(apiKey = "test", model = "gpt-4o-mini")
        val body = client.buildRequestJson(listOf(
            userMessage("Compare these.", listOf(
                ImagePart(pngBase64, ImagePart.WireMime.Png),
                ImagePart(pngBase64, ImagePart.WireMime.Jpeg),
            )),
        ))
        @Suppress("UNCHECKED_CAST")
        val parsed = LenientJsonParser.parse(body) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val messages = parsed["messages"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val content = messages.single()["content"] as List<Map<String, Any?>>
        assertEquals(3, content.size, "text + 2 images")
        @Suppress("UNCHECKED_CAST")
        val u1 = (content[1]["image_url"] as Map<String, Any?>)["url"] as String
        @Suppress("UNCHECKED_CAST")
        val u2 = (content[2]["image_url"] as Map<String, Any?>)["url"] as String
        assertTrue(u1.startsWith("data:image/png;base64,"))
        assertTrue(u2.startsWith("data:image/jpeg;base64,"), "second image's wireMime propagates")
    }

    // ────────────────────────── Fixtures sanity ─────────────────────────

    @Test
    fun `VisionFixtures threeSquaresPng renders a valid PNG of reasonable size`() {
        val bytes = VisionFixtures.threeSquaresPng()
        // PNG magic: 89 50 4E 47 0D 0A 1A 0A
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals(0x50.toByte(), bytes[1])
        assertEquals(0x4E.toByte(), bytes[2])
        assertEquals(0x47.toByte(), bytes[3])
        assertTrue(bytes.size > 100 && bytes.size < 50_000, "small enough for cheap vision calls; got ${bytes.size}")
    }

    @Test
    fun `VisionFixtures housePng renders a valid PNG`() {
        val bytes = VisionFixtures.housePng()
        assertEquals(0x89.toByte(), bytes[0])
        assertTrue(bytes.size in 100..50_000)
    }
}
