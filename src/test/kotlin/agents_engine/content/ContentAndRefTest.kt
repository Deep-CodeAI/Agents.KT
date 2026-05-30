package agents_engine.content

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2466 + #2467 — typed Content hierarchy + ContentRef + BlobStore. Pins:
 *
 * 1. `Content` sealed; mime types are closed per modality (no String).
 * 2. `ContentRef` carries hash + size + wire mime; equatable.
 * 3. `computeContentHash` is deterministic and matches the store's
 *    `put` outcome.
 * 4. `InMemoryBlobStore` round-trips bytes; identical bytes → same ref
 *    (dedupe); defensive copies on put/get protect against mutation.
 * 5. `FileBlobStore` survives process restart (i.e. a fresh instance
 *    on the same dir sees prior puts).
 * 6. The `modality` extension property is stable per variant.
 */
class ContentAndRefTest {

    private val sampleBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    @Test
    fun `computeContentHash is deterministic`() {
        val h1 = computeContentHash(sampleBytes)
        val h2 = computeContentHash(sampleBytes.copyOf())
        assertEquals(h1, h2, "same bytes → same hash")
        assertEquals(64, h1.length, "SHA-256 hex is 64 chars")
        assertTrue(h1.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `InMemoryBlobStore round-trips bytes and produces equatable refs`() {
        val store = InMemoryBlobStore()
        val ref1 = store.put(sampleBytes, ImageMime.Png.wireMime)
        val ref2 = store.put(sampleBytes.copyOf(), ImageMime.Png.wireMime)
        assertEquals(ref1, ref2, "same bytes → same ContentRef (dedupe)")
        val read = store.get(ref1)
        assertNotNull(read)
        assertTrue(sampleBytes.contentEquals(read), "bytes round-trip")
        assertEquals(sampleBytes.size.toLong(), ref1.sizeBytes)
        assertEquals(ImageMime.Png.wireMime, ref1.wireMime)
    }

    @Test
    fun `InMemoryBlobStore returns defensive copies (consumer mutation can't corrupt)`() {
        val store = InMemoryBlobStore()
        val mutable = sampleBytes.copyOf()
        val ref = store.put(mutable, ImageMime.Png.wireMime)
        // Mutate the input array — store's internal copy must be unaffected.
        mutable[0] = 0x00
        val read = store.get(ref)!!
        assertEquals(sampleBytes.first(), read.first(), "internal copy not affected by external mutation")
        // Mutate the returned array — subsequent gets must see clean state.
        read[0] = 0x00
        val readAgain = store.get(ref)!!
        assertEquals(sampleBytes.first(), readAgain.first(), "returned copies don't share storage")
    }

    @Test
    fun `InMemoryBlobStore exists and delete work as advertised`() {
        val store = InMemoryBlobStore()
        val ref = store.put(sampleBytes, ImageMime.Png.wireMime)
        assertTrue(store.exists(ref))
        store.delete(ref)
        assertFalse(store.exists(ref))
        assertNull(store.get(ref))
    }

    @Test
    fun `FileBlobStore survives a fresh instance on the same dir (process-restart safety)`(@TempDir tmp: Path) {
        val ref = FileBlobStore(tmp).put(sampleBytes, DocMime.Pdf.wireMime)

        // "Restart" — fresh instance reads the same dir.
        val resumed = FileBlobStore(tmp)
        assertTrue(resumed.exists(ref))
        val read = resumed.get(ref)
        assertNotNull(read)
        assertTrue(sampleBytes.contentEquals(read))
    }

    @Test
    fun `FileBlobStore deduplicates identical puts`(@TempDir tmp: Path) {
        val store = FileBlobStore(tmp)
        val ref1 = store.put(sampleBytes, ImageMime.Png.wireMime)
        val ref2 = store.put(sampleBytes, ImageMime.Png.wireMime)
        assertEquals(ref1, ref2)
        // Single file on disk — dedupe is real, not just on the ref.
        val files = java.nio.file.Files.list(tmp).use { it.toList() }
        assertEquals(1, files.size, "second put must not write a second file")
    }

    @Test
    fun `Content modality is stable per variant`() {
        val img = Content.Image(ContentRef("abc", 1, "image/png"), ImageMime.Png)
        val doc = Content.Document(ContentRef("def", 1, "application/pdf"), DocMime.Pdf)
        val text = Content.Text("hello")
        assertEquals("image", img.modality)
        assertEquals("document", doc.modality)
        assertEquals("text", text.modality)
    }

    @Test
    fun `closed mime types expose stable wire forms`() {
        assertEquals("image/png", ImageMime.Png.wireMime)
        assertEquals("image/jpeg", ImageMime.Jpeg.wireMime)
        assertEquals("application/pdf", DocMime.Pdf.wireMime)
        assertEquals("audio/mpeg", AudioMime.Mp3.wireMime)
        assertEquals("video/mp4", VideoMime.Mp4.wireMime)
    }
}
