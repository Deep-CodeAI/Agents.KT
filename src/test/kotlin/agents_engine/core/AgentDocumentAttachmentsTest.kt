package agents_engine.core

import agents_engine.content.Content
import agents_engine.content.DocMime
import agents_engine.content.InMemoryBlobStore
import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2470 slice c — `Content.Document` flows through `agent
 * .invokeWithAttachments` and lands as `LlmMessage.documents` on the
 * first user message. Per-provider wire translation is in the
 * `DocumentWireFormatTest`; this suite covers the agent-side deref
 * (Pdf / PlainText / Markdown → DocumentPart, Docx / Html dropped).
 */
class AgentDocumentAttachmentsTest {

    private fun captureFirstUserMessage(
        attachments: List<Content>,
        configureBlobStore: Boolean = true,
    ): LlmMessage {
        val captured = mutableListOf<List<LlmMessage>>()
        val mock = ModelClient { msgs -> captured += msgs.toList(); LlmResponse.Text("ok") }
        val store = InMemoryBlobStore()
        // Pre-seed the store with the bytes referenced in `attachments`.
        // The caller uses store.put() on the test bytes, so just plumb
        // the store through here.
        val a = agent<String, String>("doc-agent") {
            model { ollama("t"); client = mock }
            if (configureBlobStore) blobStore(store)
            skills { skill<String, String>("s", "") { tools() } }
        }
        // Hand the same store to caller via thread-local — simplest: caller
        // uses helper below that creates the agent and puts in one go.
        return a.invokeWithAttachments("ask", attachments).let { _ ->
            captured.first().last { it.role == "user" }
        }
    }

    /** Build agent + store together so the attachments' refs resolve. */
    private fun runAttach(attachmentsBuilder: (InMemoryBlobStore) -> List<Content>): LlmMessage {
        val captured = mutableListOf<List<LlmMessage>>()
        val mock = ModelClient { msgs -> captured += msgs.toList(); LlmResponse.Text("ok") }
        val store = InMemoryBlobStore()
        val a = agent<String, String>("doc-agent") {
            model { ollama("t"); client = mock }
            blobStore(store)
            skills { skill<String, String>("s", "") { tools() } }
        }
        val attachments = attachmentsBuilder(store)
        a.invokeWithAttachments("ask", attachments)
        return captured.first().last { it.role == "user" }
    }

    @Test
    fun `Content Document PDF dereferences into DocumentPart Pdf on first user message`() {
        val userMsg = runAttach { store ->
            val ref = store.put("%PDF-1.4\nhello".toByteArray(), DocMime.Pdf.wireMime)
            listOf(Content.Document(ref, DocMime.Pdf))
        }
        val docs = assertNotNull(userMsg.documents)
        assertEquals(1, docs.size)
        assertEquals("application/pdf", docs[0].wireMime.value)
    }

    @Test
    fun `Content Document PlainText dereferences into DocumentPart PlainText`() {
        val userMsg = runAttach { store ->
            val ref = store.put("plain text body".toByteArray(), DocMime.PlainText.wireMime)
            listOf(Content.Document(ref, DocMime.PlainText))
        }
        val docs = assertNotNull(userMsg.documents)
        assertEquals("text/plain", docs[0].wireMime.value)
    }

    @Test
    fun `Content Document Markdown dereferences into DocumentPart Markdown — adapter handles the text slash plain mapping`() {
        val userMsg = runAttach { store ->
            val ref = store.put("# md".toByteArray(), DocMime.Markdown.wireMime)
            listOf(Content.Document(ref, DocMime.Markdown))
        }
        val docs = assertNotNull(userMsg.documents)
        // The typed wireMime tracks the caller's intent (Markdown); the
        // adapter (ClaudeClient) maps it to text/plain on the wire because
        // Anthropic has no markdown media_type.
        assertEquals("text/markdown", docs[0].wireMime.value)
    }

    @Test
    fun `Content Document Docx is silently dropped (not on the wire for any provider)`() {
        val userMsg = runAttach { store ->
            val ref = store.put(byteArrayOf(0x50, 0x4B), DocMime.Docx.wireMime)
            listOf(Content.Document(ref, DocMime.Docx))
        }
        assertNull(userMsg.documents, "Docx drops at agent deref — no provider wire path")
    }

    @Test
    fun `Content Document Html is silently dropped (not on the wire for any provider)`() {
        val userMsg = runAttach { store ->
            val ref = store.put("<html>".toByteArray(), DocMime.Html.wireMime)
            listOf(Content.Document(ref, DocMime.Html))
        }
        assertNull(userMsg.documents, "Html drops at agent deref")
    }

    @Test
    fun `images and documents in one attachments list each land on their own LlmMessage field`() {
        val userMsg = runAttach { store ->
            val pngRef = store.put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), agents_engine.content.ImageMime.Png.wireMime)
            val pdfRef = store.put("%PDF-1.4\nspec".toByteArray(), DocMime.Pdf.wireMime)
            listOf(
                Content.Image(pngRef, agents_engine.content.ImageMime.Png),
                Content.Document(pdfRef, DocMime.Pdf),
            )
        }
        assertNotNull(userMsg.images, "image flows to images field")
        assertEquals(1, userMsg.images!!.size)
        assertNotNull(userMsg.documents, "document flows to documents field")
        assertEquals(1, userMsg.documents!!.size)
    }

    @Test
    fun `multiple documents preserve order in the documents list`() {
        val userMsg = runAttach { store ->
            val ref1 = store.put("first".toByteArray(), DocMime.PlainText.wireMime)
            val ref2 = store.put("second".toByteArray(), DocMime.Pdf.wireMime)
            listOf(
                Content.Document(ref1, DocMime.PlainText),
                Content.Document(ref2, DocMime.Pdf),
            )
        }
        val docs = assertNotNull(userMsg.documents)
        assertEquals(2, docs.size)
        assertEquals("text/plain", docs[0].wireMime.value)
        assertEquals("application/pdf", docs[1].wireMime.value)
    }

    @Test
    fun `documents without a blobStore fail fast with the same message as images`() {
        val ex = kotlin.runCatching {
            captureFirstUserMessage(
                attachments = listOf(
                    Content.Document(
                        ref = InMemoryBlobStore().put("x".toByteArray(), DocMime.Pdf.wireMime),
                        mime = DocMime.Pdf,
                    ),
                ),
                configureBlobStore = false,
            )
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("blobStore" in (ex.message ?: ""), "error names the missing config: ${ex.message}")
    }
}
