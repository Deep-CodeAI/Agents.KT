package agents_engine.core

import agents_engine.content.Content
import agents_engine.content.ImageMime
import agents_engine.content.InMemoryBlobStore
import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #2470 slice b — `agent.invokeWithAttachments(input, attachments)`
 * dereferences Content.Image against the agent's BlobStore and rides
 * along as `LlmMessage.images` on the first user message. Pins:
 *
 * 1. attachments = listOf(Content.Image(ref, mime)) → first user
 *    LlmMessage carries an ImagePart with the dereferenced base64 +
 *    typed wire mime.
 * 2. The text input is unchanged — toLlmInput(input) still controls
 *    the message.content.
 * 3. Closed mime mapping (ImageMime → ImagePart.WireMime) for all four
 *    variants.
 * 4. Multiple images compose; ordering preserved.
 * 5. Non-image Content variants (Document/Audio/Video/Text) are
 *    skipped in v1 — no provider-doc/audio/video path yet.
 * 6. attachments without a configured BlobStore fails fast with a
 *    clear error.
 * 7. Ref pointing at a missing blob fails fast with a forensic-friendly
 *    error message.
 * 8. invokeSuspend (legacy entry, no attachments) stays byte-identical:
 *    user message carries `images = null`.
 */
class AgentAttachmentsTest {

    private val redPng = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG magic
        1, 2, 3, 4, 5,
    )

    private fun captureFirstUserMessage(
        configure: Agent<String, String>.() -> Unit = { },
        attachments: List<Content>? = null,
    ): LlmMessage {
        val captured = mutableListOf<List<LlmMessage>>()
        val mock = ModelClient { msgs -> captured += msgs.toList(); LlmResponse.Text("done") }
        val a = agent<String, String>("a") {
            model { ollama("t"); client = mock }
            skills { skill<String, String>("s", "") { tools() } }
            configure()
        }
        if (attachments != null) a.invokeWithAttachments("hi", attachments) else a("hi")
        return captured.first().last { it.role == "user" }
    }

    @Test
    fun `invokeWithAttachments dereferences Content Image into ImagePart on first user message`() {
        val store = InMemoryBlobStore()
        val ref = store.put(redPng, ImageMime.Png.wireMime)
        val userMsg = captureFirstUserMessage(
            configure = { blobStore(store) },
            attachments = listOf(Content.Image(ref, ImageMime.Png)),
        )
        val images = assertNotNull(userMsg.images, "first user msg carries images")
        assertEquals(1, images.size)
        val expectedBase64 = java.util.Base64.getEncoder().encodeToString(redPng)
        assertEquals(expectedBase64, images[0].base64)
        assertEquals("image/png", images[0].wireMime.value)
        assertEquals("hi", userMsg.content, "text input untouched by attachments")
    }

    @Test
    fun `closed ImageMime maps to closed ImagePart WireMime for all four variants`() {
        val store = InMemoryBlobStore()
        val jpegBytes = byteArrayOf(1, 2, 3)
        val gifBytes = byteArrayOf(4, 5, 6)
        val webpBytes = byteArrayOf(7, 8, 9)
        val pngBytes = byteArrayOf(10, 11, 12)
        val refs = listOf(
            Content.Image(store.put(pngBytes, ImageMime.Png.wireMime), ImageMime.Png),
            Content.Image(store.put(jpegBytes, ImageMime.Jpeg.wireMime), ImageMime.Jpeg),
            Content.Image(store.put(gifBytes, ImageMime.Gif.wireMime), ImageMime.Gif),
            Content.Image(store.put(webpBytes, ImageMime.Webp.wireMime), ImageMime.Webp),
        )
        val userMsg = captureFirstUserMessage(
            configure = { blobStore(store) },
            attachments = refs,
        )
        val images = assertNotNull(userMsg.images)
        assertEquals(4, images.size)
        assertEquals("image/png", images[0].wireMime.value)
        assertEquals("image/jpeg", images[1].wireMime.value)
        assertEquals("image/gif", images[2].wireMime.value)
        assertEquals("image/webp", images[3].wireMime.value)
    }

    @Test
    fun `multiple images compose in order — non-image content variants are skipped in v1`() {
        val store = InMemoryBlobStore()
        val ref1 = store.put(byteArrayOf(1, 2), ImageMime.Png.wireMime)
        val ref2 = store.put(byteArrayOf(3, 4), ImageMime.Png.wireMime)
        val userMsg = captureFirstUserMessage(
            configure = { blobStore(store) },
            attachments = listOf(
                Content.Text("ignored — text variant"),
                Content.Image(ref1, ImageMime.Png),
                Content.Image(ref2, ImageMime.Png),
                // Document / Audio / Video variants need refs we don't make
                // here; the production runtime would skip them too in v1.
            ),
        )
        val images = assertNotNull(userMsg.images)
        assertEquals(2, images.size, "two images, text skipped")
        assertEquals(java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2)), images[0].base64)
        assertEquals(java.util.Base64.getEncoder().encodeToString(byteArrayOf(3, 4)), images[1].base64)
    }

    @Test
    fun `attachments without a blobStore fail fast with a clear message`() {
        val ref = InMemoryBlobStore().put(byteArrayOf(1, 2), ImageMime.Png.wireMime)
        val ex = assertThrows<IllegalArgumentException> {
            captureFirstUserMessage(
                configure = { /* no blobStore */ },
                attachments = listOf(Content.Image(ref, ImageMime.Png)),
            )
        }
        val msg = ex.message ?: ""
        assertTrue("blobStore" in msg, "error names the missing config: $msg")
    }

    @Test
    fun `ref pointing at a missing blob fails fast with hash context for forensics`() {
        val store = InMemoryBlobStore()
        val ref = store.put(byteArrayOf(1, 2, 3), ImageMime.Png.wireMime)
        store.delete(ref)
        val ex = assertThrows<IllegalStateException> {
            captureFirstUserMessage(
                configure = { blobStore(store) },
                attachments = listOf(Content.Image(ref, ImageMime.Png)),
            )
        }
        val msg = ex.message ?: ""
        assertTrue(ref.hash.take(8) in msg, "error names the ref's hash: $msg")
    }

    @Test
    fun `invokeSuspend without attachments stays byte-identical (back-compat)`() {
        val userMsg = captureFirstUserMessage()
        assertEquals("hi", userMsg.content)
        assertEquals(null, userMsg.images, "no images = null field; wire shape unchanged")
    }

    @Test
    fun `empty attachments list is treated as no attachments`() {
        val userMsg = captureFirstUserMessage(
            configure = { blobStore(InMemoryBlobStore()) },
            attachments = emptyList(),
        )
        assertEquals(null, userMsg.images, "empty list short-circuits the deref path")
    }

    @Test
    fun `attachments list with only non-image variants results in null images (no providers see empty array)`() {
        val store = InMemoryBlobStore()
        val userMsg = captureFirstUserMessage(
            configure = { blobStore(store) },
            attachments = listOf(Content.Text("only text")),
        )
        assertEquals(null, userMsg.images, "skipping all variants → null, not empty list")
    }
}
