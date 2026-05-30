package agents_engine.core

import agents_engine.model.ImagePart
import agents_engine.model.LlmMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2866 — regression coverage for the SnapshotJson encoder + decoder
 * round-tripping `LlmMessage.images`.
 *
 * Pre-#2866 the encoder serialised only `role / content / toolCalls`
 * per message. File-backed `snapshot/resume` silently lost any vision
 * attachments that were on the conversation when the snapshot was
 * taken. SSE-style apps mid-conversation lost the image context on
 * resume.
 *
 * The fix encodes each `ImagePart` as `{base64, mime}` directly inside
 * the message (rather than ContentRef + BlobStore re-fetch) so the
 * snapshot stays self-contained — no BlobStore dependency at resume.
 */
class SnapshotImagesRoundTripTest {

    @Test
    fun `single image round-trips through encode-decode`() {
        val original = SessionSnapshot(
            messages = listOf(
                LlmMessage(
                    role = "user",
                    content = "What's in this image?",
                    images = listOf(
                        ImagePart(base64 = "iVBORw0KGgoAAAANSUhEUg==", wireMime = ImagePart.WireMime.Png),
                    ),
                ),
            ),
            turns = 1,
            toolCalls = 0,
            toolCallLimit = 0,
            tokensUsed = null,
            memory = emptyMap(),
            requestId = "req-1", sessionId = null, manifestHash = null,
        )

        val encoded = SnapshotJson.encode(original)
        val decoded = SnapshotJson.decode(encoded)

        assertEquals(1, decoded.messages.size)
        val msg = decoded.messages.single()
        assertEquals("user", msg.role)
        assertEquals("What's in this image?", msg.content)
        val images = msg.images
        assertNotNull(images, "images must rehydrate")
        assertEquals(1, images.size)
        assertEquals("iVBORw0KGgoAAAANSUhEUg==", images.single().base64)
        assertEquals(ImagePart.WireMime.Png, images.single().wireMime)
    }

    @Test
    fun `multiple images of mixed mimes round-trip in order`() {
        val original = SessionSnapshot(
            messages = listOf(
                LlmMessage(
                    role = "user",
                    content = "Compare these.",
                    images = listOf(
                        ImagePart(base64 = "AAA1", wireMime = ImagePart.WireMime.Png),
                        ImagePart(base64 = "BBB2", wireMime = ImagePart.WireMime.Jpeg),
                        ImagePart(base64 = "CCC3", wireMime = ImagePart.WireMime.Webp),
                        ImagePart(base64 = "DDD4", wireMime = ImagePart.WireMime.Gif),
                    ),
                ),
            ),
            turns = 1,
            toolCalls = 0,
            toolCallLimit = 0,
            tokensUsed = null,
            memory = emptyMap(),
            requestId = "req-2", sessionId = null, manifestHash = null,
        )

        val decoded = SnapshotJson.decode(SnapshotJson.encode(original))
        val images = decoded.messages.single().images!!
        assertEquals(listOf("AAA1", "BBB2", "CCC3", "DDD4"), images.map { it.base64 })
        assertEquals(
            listOf(
                ImagePart.WireMime.Png,
                ImagePart.WireMime.Jpeg,
                ImagePart.WireMime.Webp,
                ImagePart.WireMime.Gif,
            ),
            images.map { it.wireMime },
        )
    }

    @Test
    fun `message with no images round-trips with null images field — back-compat`() {
        val original = SessionSnapshot(
            messages = listOf(LlmMessage(role = "user", content = "no vision")),
            turns = 1,
            toolCalls = 0,
            toolCallLimit = 0,
            tokensUsed = null,
            memory = emptyMap(),
            requestId = "req-3", sessionId = null, manifestHash = null,
        )

        val encoded = SnapshotJson.encode(original)
        // Wire shape: when images is null, the `images` key must be omitted —
        // this preserves byte-identity with pre-#2866 snapshots.
        assertTrue(
            !encoded.contains("\"images\""),
            "no images → no `images` key in the JSON (back-compat): $encoded",
        )

        val decoded = SnapshotJson.decode(encoded)
        assertNull(decoded.messages.single().images)
    }

    @Test
    fun `pre-2866 snapshots without an images key decode with null images`() {
        // Hand-crafted legacy snapshot shape — older clients that saved
        // before #2866 should still load cleanly.
        val legacy = """{
            "messages":[{"role":"user","content":"hi"}],
            "turns":0,
            "toolCalls":0,
            "toolCallLimit":0,
            "memory":{},
            "requestId":"legacy-1"
        }""".trimIndent()

        val decoded = SnapshotJson.decode(legacy)
        assertEquals(1, decoded.messages.size)
        assertNull(decoded.messages.single().images, "legacy snapshots decode with null images")
    }

    @Test
    fun `unknown image mime values are skipped on decode — defensive`() {
        // A future-extended or hand-edited snapshot with an unknown mime
        // should NOT crash resume — the unknown part is dropped, the rest
        // of the message rehydrates fine.
        val custom = """{
            "messages":[{
                "role":"user",
                "content":"mixed-mime",
                "images":[
                    {"base64":"OK1","mime":"image/png"},
                    {"base64":"OK2","mime":"image/unknown-future-format"},
                    {"base64":"OK3","mime":"image/jpeg"}
                ]
            }],
            "turns":0,"toolCalls":0,"toolCallLimit":0,"memory":{},"requestId":"r"
        }""".trimIndent()

        val decoded = SnapshotJson.decode(custom)
        val images = decoded.messages.single().images!!
        assertEquals(2, images.size, "the unknown-mime entry should be skipped")
        assertEquals(listOf("OK1", "OK3"), images.map { it.base64 })
    }
}
