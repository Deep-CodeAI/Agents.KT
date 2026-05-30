package agents_engine.model

import agents_engine.generation.LenientJsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2470 slice c — document-input wire-format tests. Anthropic Claude
 * is the only provider with a native document content block on the
 * /v1/messages endpoint today; OpenAI Chat Completions and Ollama
 * drop documents silently with a one-shot JUL warning.
 *
 * Pins:
 * - Claude PDF document block: media_type=application/pdf, base64
 *   data, type=document.
 * - Claude PlainText document block: media_type=text/plain.
 * - Claude Markdown document → media_type=text/plain on the wire
 *   (Anthropic has no separate text/markdown document type).
 * - Claude images + documents in one user message compose as a content
 *   array (text + image blocks + document blocks).
 * - OpenAI documents-only message produces no document block in the
 *   payload; legacy text-content shape preserved.
 * - Ollama same; the warning latch doesn't break the wire shape.
 */
class DocumentWireFormatTest {

    private val pdfB64 = "JVBERi0xLjQK" // "%PDF-1.4\n" — just a token; tests
                                          // verify the wire shape, not real PDF parsing.
    private val textB64 = java.util.Base64.getEncoder()
        .encodeToString("The secret word is pineapple.".toByteArray())
    private val mdB64 = java.util.Base64.getEncoder()
        .encodeToString("# Heading\nThe magic number is 42.".toByteArray())

    private fun userMessage(text: String, documents: List<DocumentPart>) =
        LlmMessage(role = "user", content = text, documents = documents)

    // ───────────────────────── Claude ─────────────────────────

    @Test
    fun `Claude PDF document block carries base64 + application slash pdf media_type`() {
        val client = ClaudeClient(apiKey = "test", model = "claude-haiku-4-5")
        val body = client.buildRequestJson(listOf(
            userMessage("What does this PDF say?", listOf(
                DocumentPart(pdfB64, DocumentPart.WireMime.Pdf),
            )),
        ))
        @Suppress("UNCHECKED_CAST")
        val parsed = LenientJsonParser.parse(body) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val messages = parsed["messages"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val content = messages.single()["content"] as List<Map<String, Any?>>
        assertEquals(2, content.size, "text + document")
        assertEquals("text", content[0]["type"])
        assertEquals("document", content[1]["type"])
        @Suppress("UNCHECKED_CAST")
        val source = content[1]["source"] as Map<String, Any?>
        assertEquals("base64", source["type"])
        assertEquals("application/pdf", source["media_type"])
        assertEquals(pdfB64, source["data"])
    }

    @Test
    fun `Claude PlainText document uses source type=text with raw text data (Anthropic only accepts base64 for PDFs)`() {
        val client = ClaudeClient(apiKey = "test", model = "claude-haiku-4-5")
        val body = client.buildRequestJson(listOf(
            userMessage("What's the secret word?", listOf(
                DocumentPart(textB64, DocumentPart.WireMime.PlainText),
            )),
        ))
        @Suppress("UNCHECKED_CAST")
        val parsed = LenientJsonParser.parse(body) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val messages = parsed["messages"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val content = messages.single()["content"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val source = content[1]["source"] as Map<String, Any?>
        assertEquals("text", source["type"], "text docs use source.type=text, not base64")
        assertEquals("text/plain", source["media_type"])
        // data is the DECODED raw text, not base64 — Anthropic requires this on text source.
        assertEquals("The secret word is pineapple.", source["data"])
    }

    @Test
    fun `Claude Markdown document also uses source type=text with text slash plain media_type`() {
        val client = ClaudeClient(apiKey = "test", model = "claude-haiku-4-5")
        val body = client.buildRequestJson(listOf(
            userMessage("Summarise the document.", listOf(
                DocumentPart(mdB64, DocumentPart.WireMime.Markdown),
            )),
        ))
        @Suppress("UNCHECKED_CAST")
        val parsed = LenientJsonParser.parse(body) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val messages = parsed["messages"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val content = messages.single()["content"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val source = content[1]["source"] as Map<String, Any?>
        assertEquals("text", source["type"], "Markdown also uses text source")
        assertEquals("text/plain", source["media_type"],
            "Markdown DocumentPart → text/plain on the Anthropic wire")
        // The markdown content is decoded for the wire
        val data = source["data"] as String
        assertTrue("# Heading" in data, "decoded markdown body present: $data")
    }

    @Test
    fun `Claude images and documents combine into one content array (text + image + document blocks)`() {
        val client = ClaudeClient(apiKey = "test", model = "claude-haiku-4-5")
        val body = client.buildRequestJson(listOf(
            LlmMessage(
                role = "user",
                content = "Look at the chart in this image alongside the PDF spec.",
                images = listOf(ImagePart("aW1hZ2UtYg==", ImagePart.WireMime.Png)),
                documents = listOf(DocumentPart(pdfB64, DocumentPart.WireMime.Pdf)),
            ),
        ))
        @Suppress("UNCHECKED_CAST")
        val parsed = LenientJsonParser.parse(body) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val messages = parsed["messages"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val content = messages.single()["content"] as List<Map<String, Any?>>
        assertEquals(3, content.size, "text + image + document")
        assertEquals("text", content[0]["type"])
        assertEquals("image", content[1]["type"])
        assertEquals("document", content[2]["type"])
    }

    @Test
    fun `Claude user message with no images and no documents stays on the legacy string-content path`() {
        val client = ClaudeClient(apiKey = "test", model = "claude-haiku-4-5")
        val body = client.buildRequestJson(listOf(LlmMessage(role = "user", content = "plain ask")))
        @Suppress("UNCHECKED_CAST")
        val parsed = LenientJsonParser.parse(body) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val messages = parsed["messages"] as List<Map<String, Any?>>
        assertEquals("plain ask", messages.single()["content"],
            "back-compat: string content when neither images nor documents")
    }

    // ───────────────────────── OpenAI ─────────────────────────

    @Test
    fun `OpenAI user message with documents drops them from the wire (no document content type on Chat Completions)`() {
        val client = OpenAiClient(apiKey = "test", model = "gpt-4o-mini")
        val body = client.buildRequestJson(listOf(
            userMessage("Read this", listOf(
                DocumentPart(pdfB64, DocumentPart.WireMime.Pdf),
            )),
        ))
        // No mentions of "document" or PDF media_type — slice c contract: drop silently
        assertTrue("\"type\":\"document\"" !in body,
            "OpenAI Chat Completions has no document content block: $body")
        assertTrue("application/pdf" !in body,
            "PDF media_type must not appear in OpenAI request: $body")
    }

    @Test
    fun `OpenAI documents + images compose — documents drop, images keep their existing image_url shape`() {
        val client = OpenAiClient(apiKey = "test", model = "gpt-4o-mini")
        val body = client.buildRequestJson(listOf(
            LlmMessage(
                role = "user",
                content = "Combined",
                images = listOf(ImagePart("aW1nLWI=", ImagePart.WireMime.Png)),
                documents = listOf(DocumentPart(pdfB64, DocumentPart.WireMime.Pdf)),
            ),
        ))
        assertTrue("\"type\":\"image_url\"" in body, "image still emitted: $body")
        assertTrue("\"type\":\"document\"" !in body, "document still dropped: $body")
    }

    // ───────────────────────── Ollama ─────────────────────────

    @Test
    fun `Ollama user message with documents drops them silently (no document field on Ollama chat)`() {
        val client = OllamaClient(model = "qwen3-vl:8b")
        val body = client.buildRequestJson(listOf(
            userMessage("Read this", listOf(
                DocumentPart(textB64, DocumentPart.WireMime.PlainText),
            )),
        ))
        assertTrue("\"document\"" !in body,
            "Ollama has no document field; documents must drop: $body")
    }
}
